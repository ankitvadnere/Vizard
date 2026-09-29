package com.vizard.execution;

import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.ExecutionStatus;
import com.vizard.api.dto.SourceProblem;
import com.vizard.config.ExecutionProperties;
import com.vizard.execution.analysis.SourceAnalysis;
import com.vizard.execution.analysis.SourceAnalyzer;
import com.vizard.execution.compile.CompilationResult;
import com.vizard.execution.compile.JavaCompilerService;
import com.vizard.execution.sandbox.ExecutionSandbox;
import com.vizard.execution.sandbox.SandboxRequest;
import com.vizard.execution.sandbox.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The execution pipeline:
 * <pre>
 *   source → parse + safety check → compile → run in sandbox → classify outcome
 * </pre>
 * Each step can stop the pipeline with a precise status. Milestone 2 adds an
 * "instrument" step between safety check and compile.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final SourceAnalyzer analyzer;
    private final JavaCompilerService compiler;
    private final ExecutionSandbox sandbox;
    private final RunOutcomeClassifier classifier;
    private final Semaphore runPermits;

    public ExecutionService(SourceAnalyzer analyzer, JavaCompilerService compiler, ExecutionSandbox sandbox,
                            RunOutcomeClassifier classifier, ExecutionProperties props) {
        this.analyzer = analyzer;
        this.compiler = compiler;
        this.sandbox = sandbox;
        this.classifier = classifier;
        this.runPermits = new Semaphore(Math.max(1, props.maxConcurrentRuns()));
    }

    public ExecutionResponse execute(String code, String stdin) {
        boolean acquired = false;
        Path workspace = null;
        try {
            // Limit simultaneous runs so a few browser tabs can't overload the laptop.
            acquired = runPermits.tryAcquire(3, TimeUnit.SECONDS);
            if (!acquired) {
                return ExecutionResponse.of(ExecutionStatus.SERVER_BUSY,
                        "Vizard is busy running other programs. Try again in a moment.");
            }

            SourceAnalysis analysis = analyzer.analyze(code);

            workspace = Files.createTempDirectory("vizard-");
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
                    return compilationError(javac);
                }
                return ExecutionResponse.withProblems(ExecutionStatus.UNSUPPORTED_FEATURE,
                        "This Java syntax is currently not supported by Vizard.",
                        analysis.parseProblems(), javac.durationMs());
            }

            // 2. Teaching-subset / safety policy.
            if (!analysis.violations().isEmpty()) {
                return ExecutionResponse.withProblems(ExecutionStatus.UNSUPPORTED_FEATURE,
                        "This Java feature is currently not supported by Vizard.",
                        analysis.violations(), 0);
            }

            // 3. Entry point.
            if (analysis.mainClassName() == null) {
                return ExecutionResponse.withProblems(ExecutionStatus.COMPILATION_ERROR,
                        "No entry point found.",
                        List.of(SourceProblem.error(1, 1,
                                "Add a method: public static void main(String[] args)")), 0);
            }

            // 4. Compile.
            CompilationResult compiled = compiler.compile(sourceFile, classesDir);
            if (!compiled.success()) {
                return compilationError(compiled);
            }

            // 5. Run in the sandbox and interpret what happened.
            SandboxResult result = sandbox.run(new SandboxRequest(
                    classesDir, analysis.mainClassName(), runDir, stdin == null ? "" : stdin));
            return classifier.classify(result, fileName, compiled.durationMs());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResponse.of(ExecutionStatus.INTERNAL_ERROR, "Execution was interrupted.");
        } catch (IOException e) {
            log.error("Workspace I/O failed", e);
            return ExecutionResponse.of(ExecutionStatus.INTERNAL_ERROR, "Could not prepare the workspace: " + e.getMessage());
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
                    // On Windows a just-killed process can hold a file briefly; temp dir is cleaned by the OS later.
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
