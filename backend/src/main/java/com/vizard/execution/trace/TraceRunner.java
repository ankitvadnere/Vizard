package com.vizard.execution.trace;

import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.api.dto.analysis.InputSize;
import com.vizard.api.dto.analysis.OperationCost;
import com.vizard.api.dto.analysis.StructureUse;
import com.vizard.api.dto.analysis.ProgramAnalysis;
import com.vizard.api.dto.trace.FrameSnapshot;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.StructureOperation;
import com.vizard.api.dto.trace.VariableSnapshot;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.config.ExecutionProperties;
import com.vizard.execution.PreparedProgram;
import com.vizard.execution.RunOutcomeClassifier;
import com.vizard.execution.algorithms.AlgorithmCatalog;
import com.vizard.execution.algorithms.StructureCosts;
import com.vizard.execution.algorithms.StructureFacts;
import com.vizard.execution.insight.CodeModel;
import com.vizard.execution.insight.StructureRoles;
import com.vizard.execution.insight.TraceAnnotator;
import com.vizard.execution.sandbox.ExecutionSandbox;
import com.vizard.execution.sandbox.RunningProgram;
import com.vizard.execution.sandbox.SandboxRequest;
import com.vizard.execution.sandbox.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                        program.launcherClassName(), props.maxTraceSteps(), program.collectionCallLines());
                if (recording.truncated()) {
                    // The rest runs untraced; give it the normal run limit, not the longer trace limit,
                    // so an infinite loop doesn't keep the user waiting.
                    running.limitRemainingTo(props.timeoutMs());
                }
                SandboxResult result = hideLauncherFrames(running.awaitCompletion());

                ExecutionResponse execution = classifier.classify(result, program.sourceFileName(),
                        program.compileTimeMs());
                List<TraceStep> steps = annotate(StructureRoles.apply(
                        OutputOffsets.toCharacterOffsets(recording.steps(), result.stdout())), program.codeModel());
                return new TraceResponse(execution, steps, recording.truncated(), props.maxTraceSteps(),
                        analyse(program, steps));
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

    /** Recognised algorithms with their complexity, with the bounds evaluated for this run's n. */
    private static ProgramAnalysis analyse(PreparedProgram program, List<TraceStep> steps) {
        InputSize input = inputSize(steps);
        Integer n = input == null ? null : input.n();
        StructureFacts facts = structureFacts(steps);
        List<AlgorithmMatch> matches = program.algorithms().stream()
                .map(d -> AlgorithmCatalog.describe(d, n, facts))
                .toList();
        return new ProgramAnalysis(matches, input, structureUses(steps));
    }

    /**
     * The collections the program called methods on, with how often each call was made and its cost.
     * Collections with the same variable, class and role (one per call of a method) share a row.
     */
    static List<StructureUse> structureUses(List<TraceStep> steps) {
        Map<String, Map<String, int[]>> counts = new LinkedHashMap<>(); // row → "method/argCount" → count
        Map<String, String[]> rows = new HashMap<>();                    // row → {variable, type, role}
        Map<Long, String> rowOf = new HashMap<>();
        for (TraceStep step : steps) {
            for (StructureOperation op : step.operations()) {
                String row = rowOf.computeIfAbsent(op.ref(), ref -> {
                    String variable = variableFor(steps, ref);
                    String role = roleOf(steps, ref);
                    String key = variable + "|" + op.type() + "|" + role;
                    rows.putIfAbsent(key, new String[]{variable, op.type(), role});
                    return key;
                });
                counts.computeIfAbsent(row, k -> new LinkedHashMap<>())
                        .computeIfAbsent(op.method() + "/" + op.args().size(), k -> new int[1])[0]++;
            }
        }
        List<StructureUse> uses = new ArrayList<>();
        for (Map.Entry<String, Map<String, int[]>> e : counts.entrySet()) {
            String[] row = rows.get(e.getKey());
            List<OperationCost> ops = new ArrayList<>();
            for (Map.Entry<String, int[]> op : e.getValue().entrySet()) {
                String method = op.getKey().substring(0, op.getKey().indexOf('/'));
                int args = Integer.parseInt(op.getKey().substring(op.getKey().indexOf('/') + 1));
                String label = method.equals("remove") && args == 0 ? "remove()" : method;
                ops.add(new OperationCost(label, op.getValue()[0], StructureCosts.cost(row[1], method, args)));
            }
            uses.add(new StructureUse(row[0], row[1], row[2], ops, StructureCosts.note(row[1])));
        }
        return uses;
    }

    private static String variableFor(List<TraceStep> steps, long ref) {
        for (TraceStep step : steps) {
            for (FrameSnapshot frame : step.stack()) {
                for (VariableSnapshot v : frame.variables()) {
                    if (v.value().ref() != null && v.value().ref() == ref) {
                        return v.name();
                    }
                }
            }
            for (VariableSnapshot v : step.statics()) {
                if (v.value().ref() != null && v.value().ref() == ref) {
                    return v.name();
                }
            }
        }
        return null;
    }

    private static String roleOf(List<TraceStep> steps, long ref) {
        for (TraceStep step : steps) {
            HeapObjectSnapshot o = step.heap().get(Long.toString(ref));
            if (o != null) {
                return o.role();
            }
        }
        return null;
    }

    /** The largest tree (by node count) and the longest list the run built, measured from the heap. */
    static StructureFacts structureFacts(List<TraceStep> steps) {
        int bestTree = 0;
        int bestHeight = -1;
        int bestList = 0;
        for (TraceStep step : steps) {
            Map<String, HeapObjectSnapshot> heap = step.heap();
            Set<String> pointedTo = new HashSet<>();
            for (HeapObjectSnapshot o : heap.values()) {
                if (o.links() != null) {
                    for (VariableSnapshot f : o.fields()) {
                        if (o.links().contains(f.name()) && f.value().ref() != null) {
                            pointedTo.add(f.value().ref().toString());
                        }
                    }
                }
            }
            for (Map.Entry<String, HeapObjectSnapshot> e : heap.entrySet()) {
                HeapObjectSnapshot o = e.getValue();
                if (o.role() == null || pointedTo.contains(e.getKey())) {
                    continue; // only measure from roots
                }
                if (o.role().equals("tree-node")) {
                    int[] sizeHeight = measureTree(heap, o, new HashSet<>());
                    if (sizeHeight[0] > bestTree) {
                        bestTree = sizeHeight[0];
                        bestHeight = sizeHeight[1];
                    }
                } else if (o.role().equals("list-node")) {
                    int length = 0;
                    Set<Long> seen = new HashSet<>();
                    HeapObjectSnapshot node = o;
                    while (node != null && seen.add(node.id())) {
                        length++;
                        node = child(heap, node, node.links().get(0));
                    }
                    bestList = Math.max(bestList, length);
                }
            }
        }
        return new StructureFacts(bestTree, bestHeight, bestList);
    }

    /** {node count, height in edges} of the subtree, guarding against cycles. */
    private static int[] measureTree(Map<String, HeapObjectSnapshot> heap, HeapObjectSnapshot node,
                                     Set<Long> seen) {
        if (node == null || !seen.add(node.id())) {
            return new int[]{0, -1};
        }
        int count = 1;
        int height = 0;
        for (String link : node.links()) {
            int[] sub = measureTree(heap, child(heap, node, link), seen);
            count += sub[0];
            height = Math.max(height, sub[1] + 1);
        }
        return new int[]{count, height};
    }

    private static HeapObjectSnapshot child(Map<String, HeapObjectSnapshot> heap, HeapObjectSnapshot node, String link) {
        for (VariableSnapshot f : node.fields()) {
            if (f.name().equals(link) && f.value().ref() != null) {
                return heap.get(f.value().ref().toString());
            }
        }
        return null;
    }

    /**
     * n = length of the largest array any variable referred to during the run (main's empty
     * String[] args excluded). For a sort or search that is the array being processed.
     */
    static InputSize inputSize(List<TraceStep> steps) {
        String bestName = null;
        int best = -1;
        for (TraceStep step : steps) {
            for (FrameSnapshot frame : step.stack()) {
                for (VariableSnapshot v : frame.variables()) {
                    if (v.value().ref() == null || v.name().equals("this")) {
                        continue;
                    }
                    HeapObjectSnapshot object = step.heap().get(v.value().ref().toString());
                    boolean isArgs = v.name().equals("args") && frame.methodName().equals("main");
                    if (object != null && "array".equals(object.kind()) && !isArgs && object.length() > best) {
                        best = object.length();
                        bestName = v.name();
                    }
                }
            }
        }
        return best < 0 ? null : new InputSize(bestName, best);
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
