package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * Static facts about the program as a whole.
 *
 * @param algorithms recognised algorithms, in source order (may be empty)
 * @param input      input size n, or null if the program used no arrays
 * @param structures standard collections the program used, with the cost of each operation it made
 */
public record ProgramAnalysis(List<AlgorithmMatch> algorithms, InputSize input, List<StructureUse> structures) {

    public static ProgramAnalysis empty() {
        return new ProgramAnalysis(List.of(), null, List.of());
    }
}
