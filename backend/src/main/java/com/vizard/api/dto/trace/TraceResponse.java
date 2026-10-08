package com.vizard.api.dto.trace;

import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.analysis.ProgramAnalysis;

import java.util.List;

/**
 * Result of POST /api/trace.
 *
 * @param execution how the run ended: same shape as POST /api/execute (status, output, errors)
 * @param steps     recorded steps; empty if the program didn't compile or wasn't allowed
 * @param truncated true if the step limit was reached and the rest of the program ran untraced
 * @param maxSteps  the step limit in force
 * @param analysis  recognised algorithms with their complexity, and the input size
 */
public record TraceResponse(ExecutionResponse execution, List<TraceStep> steps, boolean truncated, int maxSteps,
                            ProgramAnalysis analysis) {

    public static TraceResponse withoutSteps(ExecutionResponse execution, int maxSteps) {
        return new TraceResponse(execution, List.of(), false, maxSteps, ProgramAnalysis.empty());
    }
}
