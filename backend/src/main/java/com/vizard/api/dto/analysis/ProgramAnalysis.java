package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * Static facts about the program as a whole.
 *
 * @param algorithms recognised algorithms, in source order (may be empty)
 * @param input      input size n, or null if the program used no arrays
 */
public record ProgramAnalysis(List<AlgorithmMatch> algorithms, InputSize input) {

    public static ProgramAnalysis empty() {
        return new ProgramAnalysis(List.of(), null);
    }
}
