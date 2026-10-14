package com.vizard.execution.algorithms;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.vizard.execution.insight.CodeModel.TableSpec;
import com.vizard.execution.insight.DpModelBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dynamic programming, recognised in its two forms (see {@link DpModelBuilder}), and plain Fibonacci
 * recursion, the classic example of the overlapping subproblems DP removes. The problem (knapsack,
 * LCS, edit distance, coin change, matrix chain, Fibonacci) is named from the recurrence's shape;
 * anything else with the shape is reported as dynamic programming in general.
 */
final class DpAlgorithms {

    static final String MEMO = "memo";
    static final String TABLE = "table";

    private DpAlgorithms() {
    }

    static Optional<Detection> detect(MethodDeclaration m, CompilationUnit cu) {
        if (m.getBody().isEmpty()) {
            return Optional.empty();
        }
        String name = m.getNameAsString() + "()";
        Set<String> traits = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();

        if (DpModelBuilder.memo(m).isPresent()) {
            var memo = DpModelBuilder.memo(m).get();
            traits.add(MEMO);
            traits.add(Detection.RECURSIVE);
            String problem = DpModelBuilder.classify(m, null, 0);
            evidence.add(name + " calls itself on smaller subproblems.");
            evidence.add("Line " + memo.check().getBegin().map(p -> p.line).orElse(0) + " checks " + memo.name()
                    + " first and returns the stored answer if this subproblem was solved before.");
            evidence.add("The answer is stored in " + memo.name() + " before returning, so each subproblem is "
                    + "computed once (top-down dynamic programming, memoisation).");
            Algorithm a = switch (problem) {
                case "fibonacci" -> Algorithm.FIBONACCI_MEMO;
                case "knapsack" -> Algorithm.KNAPSACK;
                case "lcs" -> Algorithm.LCS;
                case "edit-distance" -> Algorithm.EDIT_DISTANCE;
                case "coin-change" -> Algorithm.COIN_CHANGE;
                default -> Algorithm.DP_MEMO;
            };
            addProblemEvidence(a, evidence);
            return Optional.of(detection(a, m, evidence, traits));
        }

        for (TableSpec t : DpModelBuilder.tablesOf(m)) {
            if (!t.kind().equals("tabulation")) {
                continue;
            }
            traits.add(TABLE);
            traits.add("loops-" + t.loopDepth());
            evidence.add(name + " creates the table " + t.table() + (t.dims() == 2 ? "[][]" : "[]")
                    + " and fills it inside " + t.loopDepth() + (t.loopDepth() == 1 ? " loop" : " nested loops") + ".");
            t.sites().stream().filter(s -> s.write() && !s.reads().isEmpty()).findFirst().ifPresent(s ->
                    evidence.add("Line " + s.line() + " computes " + s.label() + " from cells computed earlier: "
                            + s.label() + " = " + s.shown() + " (the recurrence)."));
            evidence.add("Each cell is computed once, from the smallest subproblems up (bottom-up dynamic "
                    + "programming, tabulation).");
            Algorithm a = switch (t.problem()) {
                case "fibonacci" -> Algorithm.FIBONACCI_TABLE;
                case "knapsack" -> Algorithm.KNAPSACK;
                case "lcs" -> Algorithm.LCS;
                case "edit-distance" -> Algorithm.EDIT_DISTANCE;
                case "coin-change" -> Algorithm.COIN_CHANGE;
                case "matrix-chain" -> Algorithm.MATRIX_CHAIN;
                default -> Algorithm.DP_TABLE;
            };
            addProblemEvidence(a, evidence);
            return Optional.of(detection(a, m, evidence, traits));
        }

        if (DpModelBuilder.isPlainOverlappingRecursion(m, cu)
                && DpModelBuilder.classify(m, null, 0).equals("fibonacci")) {
            traits.add(Detection.RECURSIVE);
            evidence.add(name + " returns the sum of two recursive calls, for n − 1 and n − 2.");
            evidence.add("Nothing is remembered between calls, so the same subproblems are solved again and again "
                    + "(overlapping subproblems): the number of calls grows like the Fibonacci numbers themselves.");
            return Optional.of(detection(Algorithm.FIBONACCI_NAIVE, m, evidence, traits));
        }
        return Optional.empty();
    }

    private static void addProblemEvidence(Algorithm a, List<String> evidence) {
        switch (a) {
            case KNAPSACK -> evidence.add("Each item is either left (the value without it) or taken (its value plus "
                    + "the best for the capacity that remains); the larger wins: Math.max.");
            case LCS -> evidence.add("Equal letters extend the common subsequence by one; otherwise the better of "
                    + "dropping a letter from either string is kept (Math.max).");
            case EDIT_DISTANCE -> evidence.add("Equal letters cost nothing; otherwise one insert, delete or replace is "
                    + "added to the cheapest of the three neighbouring cells (Math.min).");
            case COIN_CHANGE -> evidence.add("Each amount looks back by the value of each coin: dp[a − coin].");
            case MATRIX_CHAIN -> evidence.add("Each chain A_i … A_j is split at every k into dp[i][k] and dp[k + 1][j], "
                    + "plus the cost of multiplying the two results.");
            case FIBONACCI_TABLE, FIBONACCI_MEMO -> evidence.add("Each value is the sum of the two before it.");
            default -> {
            }
        }
    }

    private static Detection detection(Algorithm a, MethodDeclaration m, List<String> evidence, Set<String> traits) {
        return new Detection(a, m.getNameAsString(), m.getBegin().map(p -> p.line).orElse(0), List.copyOf(evidence),
                Set.copyOf(traits));
    }
}
