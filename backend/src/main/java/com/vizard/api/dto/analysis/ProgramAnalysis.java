package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * Static facts about the program as a whole.
 *
 * @param algorithms  recognised algorithms, in source order (may be empty)
 * @param input       input size n, or null if the program used no arrays
 * @param structures  standard collections the program used, with the cost of each operation it made
 * @param searchTrees state-space trees of the backtracking searches the run made (may be empty)
 */
public record ProgramAnalysis(List<AlgorithmMatch> algorithms, InputSize input, List<StructureUse> structures,
                              List<SearchTree> searchTrees) {

    public ProgramAnalysis(List<AlgorithmMatch> algorithms, InputSize input, List<StructureUse> structures) {
        this(algorithms, input, structures, List.of());
    }

    public static ProgramAnalysis empty() {
        return new ProgramAnalysis(List.of(), null, List.of(), List.of());
    }
}
