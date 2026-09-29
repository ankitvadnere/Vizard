package com.vizard.api.dto.trace;

import com.vizard.api.dto.ExecutionResponse;

import java.util.List;

/**
 * Result of POST /api/trace.
 *
 * @param execution how the run ended: same shape as POST /api/execute (status, output, errors)
 * @param steps     recorded steps; empty if the program didn't compile or wasn't allowed
 * @param truncated true if the step limit was reached and the rest of the program ran untraced
 * @param maxSteps  the step limit in force
 */
public record TraceResponse(ExecutionResponse execution, List<TraceStep> steps, boolean truncated, int maxSteps) {

    public static TraceResponse withoutSteps(ExecutionResponse execution, int maxSteps) {
        return new TraceResponse(execution, List.of(), false, maxSteps);
    }
}
