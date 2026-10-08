package com.vizard.execution.trace;

import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.config.ExecutionProperties;
import com.vizard.execution.PreparedProgram;
import com.vizard.execution.RunOutcomeClassifier;
import com.vizard.execution.insight.CodeModel;
import com.vizard.execution.insight.TraceAnnotator;
import com.vizard.execution.sandbox.ExecutionSandbox;
import com.vizard.execution.sandbox.RunningProgram;
import com.vizard.execution.sandbox.SandboxRequest;
import com.vizard.execution.sandbox.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs a prepared program under the debugger in the same sandbox as a normal run,
 * then combines the recorded steps with the normal outcome (output, errors, timeout).
 */
@Component
public class TraceRunner {

    private static final Logger log = LoggerFactory.getLogger(TraceRunner.class);

    private final ExecutionSandbox sandbox;
    private final RunOutcomeClassifier classifier;
    private final ExecutionProperties props;

    public TraceRunner(ExecutionSandbox sandbox, RunOutcomeClassifier classifier, ExecutionProperties props) {
        this.sandbox = sandbox;
        this.classifier = classifier;
        this.props = props;
    }

    public TraceResponse trace(PreparedProgram program, String stdin)
            throws IOException, IllegalConnectorArgumentsException {
        try (JdiTraceSession session = JdiTraceSession.listen()) {
            SandboxRequest request = new SandboxRequest(program.classesDir(), program.launcherClassName(),
                    program.runDir(), stdin, props.traceTimeoutMs(), session.jvmArgs());

            try (RunningProgram running = sandbox.start(request)) {
                TraceRecording recording = session.record(running, program.userClassNames(),
                        program.launcherClassName(), props.maxTraceSteps());
                if (recording.truncated()) {
                    // The rest runs untraced; give it the normal run limit, not the longer trace limit,
                    // so an infinite loop doesn't keep the user waiting.
                    running.limitRemainingTo(props.timeoutMs());
                }
                SandboxResult result = hideLauncherFrames(running.awaitCompletion());

                ExecutionResponse execution = classifier.classify(result, program.sourceFileName(),
                        program.compileTimeMs());
                List<TraceStep> steps = annotate(
                        OutputOffsets.toCharacterOffsets(recording.steps(), result.stdout()), program.codeModel());
                return new TraceResponse(execution, steps, recording.truncated(), props.maxTraceSteps());
            }
        }
    }

    /** Adds conditions, array accesses, swaps and loop counts. Never fails the trace. */
    private static List<TraceStep> annotate(List<TraceStep> steps, CodeModel model) {
        if (model == null) {
            return steps;
        }
        try {
            return TraceAnnotator.annotate(steps, model);
        } catch (RuntimeException e) {
            log.warn("Could not annotate the trace; steps will have no insights", e);
            return steps;
        }
    }

    /** Stack traces shouldn't mention Vizard's launcher; the student never wrote it. */
    private static SandboxResult hideLauncherFrames(SandboxResult r) {
        if (!r.stderr().contains(LauncherSource.SIMPLE_NAME)) {
            return r;
        }
        String stderr = r.stderr().lines()
                .filter(line -> !line.contains(LauncherSource.SIMPLE_NAME))
                .collect(Collectors.joining("\n", "", "\n"));
        return new SandboxResult(r.stdout(), stderr, r.exitCode(), r.timedOut(), r.outputLimitExceeded(),
                r.durationMs());
    }
}
