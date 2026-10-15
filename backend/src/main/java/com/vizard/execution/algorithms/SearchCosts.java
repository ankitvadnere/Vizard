package com.vizard.execution.algorithms;

import com.vizard.api.dto.analysis.ComplexityInfo;
import com.vizard.api.dto.analysis.Measure;
import com.vizard.api.dto.analysis.OperationBound;

import java.util.ArrayList;
import java.util.List;

/**
 * Complexity of the backtracking searches, and the size of the state-space tree the search would
 * have without pruning, to compare with the nodes this run explored.
 */
record SearchCosts(ComplexityInfo complexity, OperationBound bound, List<Measure> measures) {

    private static final long TOO_BIG = Long.MAX_VALUE / 64;

    static SearchCosts of(Detection d, StructureFacts facts) {
        return of(d, facts, null);
    }

    /**
     * @param inputN the length of the program's largest array (the items of a knapsack), or null
     */
    static SearchCosts of(Detection d, StructureFacts facts, Integer inputN) {
        java.util.Map<String, Long> own = facts.of(d.method());
        Long n = own.getOrDefault("depth", facts.searchDepth());
        Long b = own.getOrDefault("branching", facts.searchBranching());
        long explored = own.getOrDefault("explored", (long) facts.nodesExplored());
        // One live counter per program: compare it with a bound only when there is a single search.
        boolean single = facts.methods() == null
                || facts.methods().values().stream().filter(m -> m.containsKey("explored")).count() <= 1;
        String metric = "nodesExplored";
        ComplexityInfo complexity;
        String formula;
        String what;
        Long full;
        String sizeText;
        switch (d.algorithm()) {
            case N_QUEENS -> {
                complexity = new ComplexityInfo("O(n!)", "O(n!)", "O(n!)", d.has(BacktrackAlgorithms.BOARD) ? "O(n²)" : "O(n)",
                        null, "Trying every column in every row would mean nⁿ placements. Placing one queen per row "
                        + "and rejecting attacked squares leaves at most n choices in the first row, n − 1 in the "
                        + "next and so on: O(n!) branches, each checked in O(n). The recursion is n levels deep"
                        + (d.has(BacktrackAlgorithms.BOARD) ? "; the board itself takes n² cells." : "."));
                formula = "1 + n + n² + … + nⁿ";
                what = "nodes in the full tree (every column in every row, no checks) at most";
                full = n == null ? null : powerSum(n, n);
                sizeText = n == null ? null : "n = " + n;
            }
            case PERMUTATIONS -> {
                complexity = new ComplexityInfo("O(n·n!)", "O(n·n!)", "O(n·n!)", "O(n)", null,
                        "There are n! orderings, each a leaf of the tree, and building or printing one takes O(n). "
                                + "The recursion is n levels deep, with O(n) for the used[] marks and the current path.");
                formula = "n!/n! + n!/(n−1)! + … + n!/0!";
                what = "nodes in the permutation tree (every partial arrangement once)";
                full = n == null ? null : permutationNodes(n);
                sizeText = n == null ? null : "n = " + n;
            }
            case SUBSETS -> {
                boolean combinations = d.has(BacktrackAlgorithms.COMBINATIONS);
                complexity = new ComplexityInfo("O(2ⁿ)", "O(2ⁿ)", "O(2ⁿ)", "O(n)", null,
                        "Every element is either in a subset or not: 2ⁿ subsets, each a "
                                + (combinations ? "node" : "leaf") + " of the tree (O(n·2ⁿ) if each one is copied or "
                                + "printed). The recursion is n levels deep."
                                + (d.has(BacktrackAlgorithms.PRUNES)
                                ? " The pruning check stops branches early, so fewer nodes are explored in practice, "
                                + "but the worst case is still exponential." : ""));
                formula = combinations ? "2ⁿ" : "2ⁿ⁺¹ − 1";
                what = combinations ? "nodes in the tree (one per subset)" : "nodes in the full take/leave tree at most";
                full = n == null || n > 60 ? null : combinations ? 1L << n : (1L << (n + 1)) - 1;
                sizeText = n == null ? null : "n = " + n;
            }
            case GRAPH_COLORING -> {
                complexity = new ComplexityInfo("O(mⁿ)", "O(mⁿ)", "O(mⁿ)", "O(n)", null,
                        "Each of the n vertices can get any of the m colours: mⁿ assignments in the worst case, and "
                                + "checking a colour against the neighbours costs O(n), so O(n·mⁿ) in total. Rejecting a "
                                + "colour that clashes with a neighbour prunes everything below it.");
                formula = "1 + m + m² + … + mⁿ";
                what = "nodes in the full tree (every colour for every vertex) at most";
                full = n == null || b == null ? null : powerSum(b, n);
                sizeText = n == null || b == null ? null : "n = " + n + " vertices, m = " + b + " colours";
            }
            case TSP_BACKTRACKING, TSP_BNB -> {
                boolean bnb = d.algorithm() == Algorithm.TSP_BNB;
                complexity = new ComplexityInfo(bnb ? "O(n!)" : "O(n!)", "O(n!)", "O(n!)", "O(n)", null,
                        "From the start city there are (n − 1)! orders of the other cities, each costing O(n) to "
                                + "build, so trying them all is O(n!)."
                                + (bnb ? " The bound (the cost so far already reaches the best tour) cuts whole "
                                + "subtrees, often most of the tree, but in the worst case nothing is cut, so the "
                                + "worst case stays O(n!)." : " Nothing is cut: every order is completed."));
                formula = "1 + (n−1) + (n−1)(n−2) + … + (n−1)!";
                what = "nodes in the full tree (every order of the other cities)";
                full = n == null ? null : permutationNodes(n);
                sizeText = n == null ? null : "n = " + (n + 1) + " cities";
            }
            case ASSIGNMENT_BACKTRACKING, ASSIGNMENT_BNB -> {
                boolean bnb = d.algorithm() == Algorithm.ASSIGNMENT_BNB;
                complexity = new ComplexityInfo("O(n!)", "O(n!)", "O(n!)", "O(n)", null,
                        "n workers get n jobs in n! possible ways."
                                + (bnb ? " The lower bound (cost so far plus the cheapest free job of every worker "
                                + "left) cuts branches that cannot beat the best assignment; the worst case is still O(n!)."
                                : ""));
                formula = "n!/n! + n!/(n−1)! + … + n!/0!";
                what = "nodes in the full tree (every partial assignment)";
                full = n == null ? null : permutationNodes(n);
                sizeText = n == null ? null : "n = " + n + " workers";
            }
            case KNAPSACK_BNB -> {
                complexity = new ComplexityInfo("O(2ⁿ)", "O(2ⁿ)", "O(2ⁿ)", "O(2ⁿ)", null,
                        "Every item is taken or left, so the full tree has 2ⁿ leaves. Each node gets an upper "
                                + "bound (the fractional-knapsack value of what is left); a node whose bound cannot beat "
                                + "the best value found is never expanded. Best-first order expands the most promising "
                                + "node first. In the worst case nothing can be pruned, and the live list can hold "
                                + "O(2ⁿ) nodes.");
                Long items = own.containsKey("items") ? own.get("items") : inputN == null ? null : (long) inputN;
                formula = "2ⁿ⁺¹ − 1";
                what = "nodes in the full take/leave tree at most";
                full = items == null || items > 60 ? null : (1L << (items + 1)) - 1;
                sizeText = items == null ? null : "n = " + items + " items";
                metric = "nodesGenerated";
                explored = own.getOrDefault("generated", 0L);
                n = items;
                b = 2L;
            }
            default -> {
                complexity = new ComplexityInfo("O(bᵈ)", "O(bᵈ)", "O(bᵈ)", "O(d)", null,
                        "A search with b choices per level and d levels has up to bᵈ leaves; pruning can only "
                                + "reduce that. The recursion is d levels deep.");
                formula = "1 + b + b² + … + bᵈ";
                what = "nodes in the full tree at most";
                full = n == null || b == null ? null : powerSum(b, n);
                sizeText = n == null || b == null ? null : "b = " + b + ", d = " + n;
            }
        }
        OperationBound bound = full == null || !single ? null
                : new OperationBound(metric, what, formula, full, "per search", sizeText);
        List<Measure> measures = new ArrayList<>();
        if (n != null) {
            measures.add(new Measure(d.algorithm() == Algorithm.GRAPH_COLORING ? "Vertices n"
                    : d.algorithm() == Algorithm.KNAPSACK_BNB ? "Items n" : "Levels n", n.toString()));
        }
        if (b != null) {
            measures.add(new Measure(d.algorithm() == Algorithm.GRAPH_COLORING ? "Colours m" : "Choices per level",
                    b.toString()));
        }
        if (explored > 0) {
            measures.add(new Measure(metric.equals("nodesGenerated") ? "Nodes generated" : "Nodes explored",
                    Long.toString(explored)));
        }
        if (own.containsKey("expanded")) {
            measures.add(new Measure("Expanded", own.get("expanded").toString()));
        }
        if (own.containsKey("pruned")) {
            measures.add(new Measure("Pruned by the bound", own.get("pruned").toString()));
        }
        if (full != null) {
            measures.add(new Measure("Full tree", Long.toString(full)));
        }
        return new SearchCosts(complexity, bound, measures);
    }

    /** 1 + b + b² + … + b^d, or null if it doesn't fit. */
    static Long powerSum(long b, long d) {
        long total = 0;
        long term = 1;
        for (long k = 0; k <= d; k++) {
            total += term;
            if (k < d) {
                if (term > TOO_BIG / Math.max(b, 1)) {
                    return null;
                }
                term *= b;
            }
            if (total > TOO_BIG) {
                return null;
            }
        }
        return total;
    }

    /** Partial arrangements of n items: Σ n!/(n−k)! for k = 0..n. */
    static Long permutationNodes(long n) {
        if (n > 20) {
            return null;
        }
        long total = 0;
        long term = 1;
        for (long k = 0; k <= n; k++) {
            total += term;
            term *= n - k;
        }
        return total;
    }
}
