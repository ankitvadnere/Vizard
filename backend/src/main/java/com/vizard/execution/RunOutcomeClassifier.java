package com.vizard.execution;

import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.ExecutionStatus;
import com.vizard.api.dto.RuntimeErrorInfo;
import com.vizard.execution.sandbox.SandboxResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw {@link SandboxResult} into a user-facing {@link ExecutionResponse}:
 * success, timeout, output flood, out-of-memory, or a runtime exception mapped to a source line.
 */
@Component
public class RunOutcomeClassifier {

    // Exception in thread "main" java.lang.ArithmeticException: / by zero
    private static final Pattern UNCAUGHT =
            Pattern.compile("Exception in thread \"main\" ([\\w.$]+)(?::\\s?(.*))?");

    public ExecutionResponse classify(SandboxResult r, String sourceFileName, long compileTimeMs) {
        if (r.timedOut()) {
            return response(ExecutionStatus.TIMEOUT,
                    "Execution stopped: the program exceeded the maximum execution time. "
                            + "Check for an infinite loop or unbounded recursion.", r, compileTimeMs, null);
        }
        if (r.outputLimitExceeded()) {
            return response(ExecutionStatus.OUTPUT_LIMIT_EXCEEDED,
                    "Execution stopped: the program printed more output than Vizard allows.",
                    r, compileTimeMs, null);
        }

        RuntimeErrorInfo error = parseUncaughtException(r.stderr(), sourceFileName);

        if (r.stderr().contains("java.lang.OutOfMemoryError")) {
            return response(ExecutionStatus.MEMORY_LIMIT_EXCEEDED,
                    "Execution stopped: the program used more memory than Vizard allows.",
                    r, compileTimeMs, error);
        }
        if (r.stderr().contains("java.lang.StackOverflowError")) {
            RuntimeErrorInfo overflow = error != null ? error
                    : new RuntimeErrorInfo("java.lang.StackOverflowError", null, 0);
            return response(ExecutionStatus.RUNTIME_ERROR,
                    "Runtime error: StackOverflowError. The recursion went too deep; check the base case.",
                    r, compileTimeMs, overflow);
        }
        if (error != null) {
            String where = error.line() > 0 ? " at line " + error.line() : "";
            return response(ExecutionStatus.RUNTIME_ERROR,
                    "Runtime error: " + simpleName(error.exceptionType()) + where + ".",
                    r, compileTimeMs, error);
        }
        if (r.exitCode() == null || r.exitCode() != 0) {
            return response(ExecutionStatus.RUNTIME_ERROR,
                    "The program ended abnormally (exit code " + r.exitCode() + ").", r, compileTimeMs, null);
        }
        return new ExecutionResponse(ExecutionStatus.SUCCESS, true, "Program finished successfully.",
                r.stdout(), r.stderr(), r.exitCode(), compileTimeMs, r.durationMs(), List.of(), null);
    }

    /** Finds the uncaught exception and the first stack frame that points into the user's file. */
    static RuntimeErrorInfo parseUncaughtException(String stderr, String sourceFileName) {
        Matcher header = UNCAUGHT.matcher(stderr);
        if (!header.find()) {
            return null;
        }
        String type = header.group(1);
        String message = header.group(2);

        int line = 0;
        Matcher frame = Pattern.compile("\\(" + Pattern.quote(sourceFileName) + ":(\\d+)\\)")
                .matcher(stderr.substring(header.end()));
        if (frame.find()) {
            line = Integer.parseInt(frame.group(1));
        }
        return new RuntimeErrorInfo(type, message == null ? null : message.strip(), line);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static ExecutionResponse response(ExecutionStatus status, String message, SandboxResult r,
                                              long compileTimeMs, RuntimeErrorInfo error) {
        return new ExecutionResponse(status, false, message, r.stdout(), r.stderr(), r.exitCode(),
                compileTimeMs, r.durationMs(), List.of(), error);
    }
}
