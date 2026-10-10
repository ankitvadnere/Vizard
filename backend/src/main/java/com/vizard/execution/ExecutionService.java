package com.vizard.execution;

import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.ExecutionStatus;
import com.vizard.api.dto.SourceProblem;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.config.ExecutionProperties;
import com.vizard.execution.analysis.SourceAnalysis;
import com.vizard.execution.analysis.SourceAnalyzer;
import com.vizard.execution.compile.CompilationResult;
import com.vizard.execution.algorithms.AlgorithmDetector;
import com.vizard.execution.algorithms.Detection;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.vizard.execution.compile.JavaCompilerService;
import com.vizard.execution.trace.CollectionCalls;
import com.vizard.execution.insight.CodeModel;
import com.vizard.execution.insight.CodeModelBuilder;
import com.vizard.execution.sandbox.ExecutionSandbox;
import com.vizard.execution.sandbox.SandboxRequest;
import com.vizard.execution.sandbox.SandboxResult;
import com.vizard.execution.trace.LauncherSource;
import com.vizard.execution.trace.TraceRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * The execution pipeline, shared by Run and Step through:
 * <pre>
 *   source → parse + safety check → compile ─┬→ run in sandbox           → classify   (Run)
 *                                             └→ run in sandbox + debugger → steps      (Step through)
 * </pre>
 * Each step can stop the pipeline with a precise status.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final SourceAnalyzer analyzer;
    private final JavaCompilerService compiler;
    private final ExecutionSandbox sandbox;
    private final RunOutcomeClassifier classifier;
    private final TraceRunner traceRunner;
    private final ExecutionProperties props;
    private final Semaphore runPermits;

    public ExecutionService(SourceAnalyzer analyzer, JavaCompilerService compiler, ExecutionSandbox sandbox,
                            RunOutcomeClassifier classifier, TraceRunner traceRunner, ExecutionProperties props) {
        this.analyzer = analyzer;
        this.compiler = compiler;
        this.sandbox = sandbox;
        this.classifier = classifier;
        this.traceRunner = traceRunner;
        this.props = props;
        this.runPermits = new Semaphore(Math.max(1, props.maxConcurrentRuns()));
    }

    /** Compiles and runs the program at full speed. */
    public ExecutionResponse execute(String code, String stdin) {
        return inWorkspace(workspace -> {
            Preparation prep = prepare(code, workspace, false);
            if (prep.failure() != null) {
                return prep.failure();
            }
            PreparedProgram program = prep.program();
            SandboxResult result = sandbox.run(new SandboxRequest(program.classesDir(), program.mainClassName(),
                    program.runDir(), stdin == null ? "" : stdin, props.timeoutMs(), List.of()));
            return classifier.classify(result, program.sourceFileName(), program.compileTimeMs());
        }, ExecutionResponse::of);
    }

    /** Compiles the program and records every step of its execution. */
    public TraceResponse trace(String code, String stdin) {
        return inWorkspace(workspace -> {
            Preparation prep = prepare(code, workspace, true);
            if (prep.failure() != null) {
                return TraceResponse.withoutSteps(prep.failure(), props.maxTraceSteps());
            }
            try {
                return traceRunner.trace(prep.program(), stdin == null ? "" : stdin);
            } catch (com.sun.jdi.connect.IllegalConnectorArgumentsException e) {
                log.error("Debugger connection failed", e);
                return TraceResponse.withoutSteps(ExecutionResponse.of(ExecutionStatus.INTERNAL_ERROR,
                        "Vizard could not attach its debugger: " + e.getMessage()), props.maxTraceSteps());
            }
        }, (status, message) -> TraceResponse.withoutSteps(ExecutionResponse.of(status, message),
                props.maxTraceSteps()));
    }

    // ---------------------------------------------------------------------------------------

    private record Preparation(PreparedProgram program, ExecutionResponse failure) {
        static Preparation failed(ExecutionResponse failure) {
            return new Preparation(null, failure);
        }
    }

    /** Parse, check, compile. Returns either a runnable program or the reason it can't run. */
    private Preparation prepare(String code, Path workspace, boolean forTracing) throws IOException {
        SourceAnalysis analysis = analyzer.analyze(code);

        Path srcDir = Files.createDirectories(workspace.resolve("src"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes"));
        Path runDir = Files.createDirectories(workspace.resolve("run"));
        String fileName = analysis.fileClassName() + ".java";
        Path sourceFile = srcDir.resolve(fileName);
        Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

        // 1. Syntax. Prefer javac's messages ("';' expected") over the parser's.
        if (!analysis.parsed()) {
            CompilationResult javac = compiler.compile(sourceFile, classesDir);
            if (!javac.success()) {
                return Preparation.failed(compilationError(javac));
            }
            return Preparation.failed(ExecutionResponse.withProblems(ExecutionStatus.UNSUPPORTED_FEATURE,
                    "This Java syntax is currently not supported by Vizard.",
                    analysis.parseProblems(), javac.durationMs()));
        }

        // 2. Teaching-subset / safety policy.
        if (!analysis.violations().isEmpty()) {
            return Preparation.failed(ExecutionResponse.withProblems(ExecutionStatus.UNSUPPORTED_FEATURE,
                    "This Java feature is currently not supported by Vizard.", analysis.violations(), 0));
        }

        // 3. Entry point.
        if (analysis.mainClassName() == null) {
            return Preparation.failed(ExecutionResponse.withProblems(ExecutionStatus.COMPILATION_ERROR,
                    "No entry point found.",
                    List.of(SourceProblem.error(1, 1, "Add a method: public static void main(String[] args)")), 0));
        }

        // 4. Compile (with the output-counting launcher when tracing).
        List<Path> sources = new ArrayList<>(List.of(sourceFile));
        String launcherClass = null;
        if (forTracing) {
            String mainSimpleName = analysis.mainClassName().substring(analysis.mainClassName().lastIndexOf('.') + 1);
            Path launcherFile = srcDir.resolve(LauncherSource.SIMPLE_NAME + ".java");
            Files.writeString(launcherFile, LauncherSource.generate(analysis.packageName(), mainSimpleName),
                    StandardCharsets.UTF_8);
            sources.add(launcherFile);
            launcherClass = LauncherSource.fullName(analysis.packageName());
        }
        CompilationResult compiled = compiler.compile(sources, classesDir, sourceFile);
        if (!compiled.success()) {
            return Preparation.failed(compilationError(compiled));
        }

        CodeModel codeModel = forTracing ? buildCodeModel(analysis) : null;
        List<Detection> algorithms = forTracing ? detectAlgorithms(analysis) : List.of();
        Set<Integer> callLines = forTracing ? collectionCallLines(analysis) : Set.of();
        return new Preparation(new PreparedProgram(classesDir, runDir, fileName, analysis.mainClassName(),
                analysis.topLevelClasses(), launcherClass, compiled.durationMs(), codeModel, algorithms,
                callLines), null);
    }

    /** Lines with a call like st.push(x) or map.get(k): where the debugger watches collection operations. */
    private static Set<Integer> collectionCallLines(SourceAnalysis analysis) {
        Set<Integer> lines = new HashSet<>();
        for (MethodCallExpr call : analysis.compilationUnit().findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent() && CollectionCalls.COUNTED_METHODS.contains(call.getNameAsString())) {
                call.getBegin().ifPresent(p -> lines.add(p.line));
                call.getName().getBegin().ifPresent(p -> lines.add(p.line)); // a chained call on its own line
            }
        }
        return Set.copyOf(lines);
    }

    /** Like insights, recognition is a bonus and must never stop a trace. */
    private static List<Detection> detectAlgorithms(SourceAnalysis analysis) {
        try {
            return AlgorithmDetector.detect(analysis.compilationUnit());
        } catch (RuntimeException e) {
            log.warn("Could not run algorithm recognition", e);
            return List.of();
        }
    }

    /** Insights are a bonus: if the analysis trips over unusual code, trace without them. */
    private static CodeModel buildCodeModel(SourceAnalysis analysis) {
        try {
            return CodeModelBuilder.build(analysis.compilationUnit());
        } catch (RuntimeException e) {
            log.warn("Could not build the code model; steps will have no insights", e);
            return CodeModel.empty();
        }
    }

    @FunctionalInterface
    private interface WorkspaceTask<T> {
        T run(Path workspace) throws IOException;
    }

    /** Takes a run slot and a fresh temp directory, runs the task, and always cleans up. */
    private <T> T inWorkspace(WorkspaceTask<T> task, BiFunction<ExecutionStatus, String, T> error) {
        boolean acquired = false;
        Path workspace = null;
        try {
            // Limit simultaneous runs so a few browser tabs can't overload the laptop.
            acquired = runPermits.tryAcquire(3, TimeUnit.SECONDS);
            if (!acquired) {
                return error.apply(ExecutionStatus.SERVER_BUSY,
                        "Vizard is busy running other programs. Try again in a moment.");
            }
            workspace = Files.createTempDirectory("vizard-");
            return task.run(workspace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error.apply(ExecutionStatus.INTERNAL_ERROR, "Execution was interrupted.");
        } catch (IOException e) {
            log.error("Workspace I/O failed", e);
            return error.apply(ExecutionStatus.INTERNAL_ERROR, "Could not prepare the workspace: " + e.getMessage());
        } finally {
            deleteQuietly(workspace);
            if (acquired) {
                runPermits.release();
            }
        }
    }

    private static ExecutionResponse compilationError(CompilationResult result) {
        long errors = result.problems().stream().filter(p -> "ERROR".equals(p.severity())).count();
        return ExecutionResponse.withProblems(ExecutionStatus.COMPILATION_ERROR,
                "Compilation failed with " + errors + (errors == 1 ? " error." : " errors."),
                result.problems(), result.durationMs());
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // On Windows a just-killed process can hold a file briefly; the OS cleans temp later.
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
