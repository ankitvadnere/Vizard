package com.vizard.execution.algorithms;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.vizard.execution.insight.BacktrackModelBuilder;
import com.vizard.execution.insight.CodeModel.SearchSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Backtracking: a recursive method that chooses, goes one level deeper, and undoes the choice
 * (see {@link BacktrackModelBuilder} for the shape). The problem decides the size of the
 * state-space tree, so it is named when the structure shows it:
 * <ul>
 *   <li>N-Queens: the choice is a cell {@code board[row][col]}, or {@code queens[row] = col} checked
 *       along diagonals</li>
 *   <li>permutations: any element not used yet ({@code !used[i]}), or swap-in / swap-back</li>
 *   <li>subsets: take or leave each element, or extend a combination with a later element</li>
 *   <li>graph colouring: {@code color[v] = c}, checked against v's row of the adjacency matrix</li>
 * </ul>
 * Anything else with the same shape is reported as a backtracking search with O(b^d).
 */
final class BacktrackAlgorithms {

    static final String BINARY = "binary";
    static final String COMBINATIONS = "combinations";
    static final String PRUNES = "prunes";
    static final String BOARD = "board";

    private BacktrackAlgorithms() {
    }

    static Optional<Detection> detect(MethodDeclaration m, CompilationUnit cu) {
        Optional<SearchSpec> found = BacktrackModelBuilder.find(m, cu);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SearchSpec s = found.get();
        Algorithm algorithm = switch (s.problem()) {
            case "n-queens" -> Algorithm.N_QUEENS;
            case "permutations" -> Algorithm.PERMUTATIONS;
            case "subsets" -> Algorithm.SUBSETS;
            case "graph-coloring" -> Algorithm.GRAPH_COLORING;
            default -> Algorithm.BACKTRACKING;
        };
        String name = s.method() + "()";
        Set<String> traits = new LinkedHashSet<>();
        traits.add(Detection.RECURSIVE);
        List<String> evidence = new ArrayList<>();
        if (s.kind().equals("binary")) {
            traits.add(BINARY);
            evidence.add(name + " calls itself twice for each element" + (s.item() == null ? "" : " " + s.item().text())
                    + ": once taking it (line " + s.includeLine() + "), once leaving it.");
        } else if (s.depthParam() != null && s.goalLine() == 0) {
            traits.add(COMBINATIONS);
            evidence.add(name + " extends the current combination with each later element (the loop at line "
                    + s.loopLine() + ") and recurses from the element after it.");
        } else {
            evidence.add(name + " tries every choice of " + s.choiceVar() + " in the loop at line " + s.loopLine()
                    + " and calls itself one level deeper"
                    + (s.depthParam() == null ? "" : " (" + s.depthParam() + " + 1)") + " for each.");
        }
        if (s.goalLine() > 0) {
            evidence.add("Line " + s.goalLine() + " is the goal test: a complete candidate has been built"
                    + (s.goalBound() == null ? "." : " when the depth reaches " + s.goalBound().text() + "."));
        }
        List<Integer> checks = new ArrayList<>(s.rejectIfFalse());
        checks.addAll(s.rejectIfTrue());
        if (!checks.isEmpty()) {
            traits.add(PRUNES);
            evidence.add("The check on line " + join(checks) + " rejects a choice before recursing, so whole "
                    + "subtrees are never explored (pruning).");
        }
        if (!s.cutLines().isEmpty()) {
            traits.add(PRUNES);
            evidence.add("Line " + join(s.cutLines()) + " abandons a call that can no longer lead to a solution "
                    + "(a bound check).");
        }
        evidence.add(s.undoLines().isEmpty()
                ? "Each new choice overwrites the previous one, so no explicit undo is needed."
                : "Line " + join(s.undoLines()) + " undoes the choice when the call returns: that is the backtrack.");
        switch (algorithm) {
            case N_QUEENS -> {
                boolean board = s.assignmentShape() != null && s.assignmentShape().startsWith("matrix");
                if (board) {
                    traits.add(BOARD);
                }
                String level = s.depthParam() == null ? "level" : s.depthParam();
                evidence.add(board
                        ? "The choice puts a queen on a square of " + s.assignment() + "[][], one queen per " + level + "."
                        : "The choice records the queen's column in " + s.assignment() + "[" + level
                        + "], checked along the diagonals.");
            }
            case PERMUTATIONS -> evidence.add("Each level picks one element that has not been used yet, so the "
                    + "leaves are the n! orderings.");
            case GRAPH_COLORING -> evidence.add("The choice gives vertex " + s.depthParam() + " colour "
                    + s.choiceVar() + " in " + s.assignment() + "[], checked against its neighbours.");
            default -> {
            }
        }
        return Optional.of(new Detection(algorithm, m.getNameAsString(),
                m.getBegin().map(p -> p.line).orElse(0), List.copyOf(evidence), Set.copyOf(traits)));
    }

    private static String join(List<Integer> lines) {
        return lines.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
