package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * Static facts about the program as a whole.
 *
 * @param algorithms  recognised algorithms, in source order (may be empty)
 * @param input       input size n, or null if the program used no arrays
 * @param structures  standard collections the program used, with the cost of each operation it made
 * @param searchTrees state-space trees of backtracking searches, and recursion trees of memoised or plain
 *                    recursion (kind "recursion" or "memo"); may be empty
 * @param dpTables    dynamic-programming tables and their fill order (may be empty)
 * @param estimates   static complexity estimates for the methods that are not recognised algorithms
 */
public record ProgramAnalysis(List<AlgorithmMatch> algorithms, InputSize input, List<StructureUse> structures,
                              List<SearchTree> searchTrees, List<DpTable> dpTables,
                              List<ComplexityEstimate> estimates) {

    public ProgramAnalysis(List<AlgorithmMatch> algorithms, InputSize input, List<StructureUse> structures,
                           List<SearchTree> searchTrees, List<DpTable> dpTables) {
        this(algorithms, input, structures, searchTrees, dpTables, List.of());
    }

    public ProgramAnalysis(List<AlgorithmMatch> algorithms, InputSize input, List<StructureUse> structures) {
        this(algorithms, input, structures, List.of(), List.of());
    }

    public static ProgramAnalysis empty() {
        return new ProgramAnalysis(List.of(), null, List.of(), List.of(), List.of(), List.of());
    }
}
