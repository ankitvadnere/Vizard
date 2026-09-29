package com.vizard.api.dto;

import java.util.List;

/**
 * Result of POST /api/execute. Always returned with HTTP 200 for anything the
 * user's program did (including crashes); the status field says what happened.
 */
public record ExecutionResponse(
        ExecutionStatus status,
        boolean success,
        String message,
        String stdout,
        String stderr,
        Integer exitCode,
        long compileTimeMs,
        long runTimeMs,
        List<SourceProblem> problems,
        RuntimeErrorInfo runtimeError
) {

    public static ExecutionResponse of(ExecutionStatus status, String message) {
        return new ExecutionResponse(status, false, message, "", "", null, 0, 0, List.of(), null);
    }

    public static ExecutionResponse withProblems(ExecutionStatus status, String message,
                                                 List<SourceProblem> problems, long compileTimeMs) {
        return new ExecutionResponse(status, false, message, "", "", null,
                compileTimeMs, 0, List.copyOf(problems), null);
    }
}
