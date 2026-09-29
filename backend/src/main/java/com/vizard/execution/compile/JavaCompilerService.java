package com.vizard.execution.compile;

import com.vizard.api.dto.SourceProblem;
import org.springframework.stereotype.Service;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compiles source files with the JDK's built-in compiler (javax.tools), in-process.
 *
 * <p>Compiling does not run user code as long as annotation processing is disabled
 * ({@code -proc:none}), so it is safe to do inside the Spring Boot process.
 */
@Service
public class JavaCompilerService {

    /** Compiles only the user's file. */
    public CompilationResult compile(Path sourceFile, Path outputDir) {
        return compile(List.of(sourceFile), outputDir, sourceFile);
    }

    /**
     * Compiles several files together (the user's file plus Vizard's generated launcher when tracing).
     * Only problems in {@code userFile} are reported as user problems; a problem in a generated
     * file is a Vizard bug and is reported as such.
     */
    public CompilationResult compile(List<Path> sourceFiles, Path outputDir, Path userFile) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No Java compiler found. Run Vizard with a JDK (not a JRE) and check JAVA_HOME.");
        }

        long start = System.nanoTime();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> options = List.of(
                    "-d", outputDir.toString(),
                    "-proc:none",          // never run annotation processors (they would execute code)
                    "-encoding", "UTF-8",
                    "-g",                  // line numbers AND local variable names (needed for tracing)
                    "-Xlint:none",
                    "-Xmaxerrs", "25");

            boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();

            List<SourceProblem> problems = new ArrayList<>();
            String internalError = null;
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                boolean isError = d.getKind() == Diagnostic.Kind.ERROR;
                boolean isWarning = d.getKind() == Diagnostic.Kind.WARNING
                        || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING;
                if (!isError && !isWarning) {
                    continue;
                }
                boolean inUserFile = d.getSource() == null
                        || Path.of(d.getSource().toUri()).getFileName().equals(userFile.getFileName());
                if (!inUserFile) {
                    if (isError && internalError == null) {
                        internalError = d.getMessage(Locale.ENGLISH);
                    }
                    continue;
                }
                int line = d.getLineNumber() == Diagnostic.NOPOS ? 0 : (int) d.getLineNumber();
                int column = d.getColumnNumber() == Diagnostic.NOPOS ? 0 : (int) d.getColumnNumber();
                problems.add(new SourceProblem(line, column, d.getMessage(Locale.ENGLISH), isError ? "ERROR" : "WARNING"));
            }

            if (!ok && problems.stream().noneMatch(p -> "ERROR".equals(p.severity()))) {
                problems.add(SourceProblem.error(0, 0,
                        "Vizard could not prepare this program for tracing: " + internalError));
            }

            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return new CompilationResult(ok, problems, durationMs);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not compile source", e);
        }
    }
}
