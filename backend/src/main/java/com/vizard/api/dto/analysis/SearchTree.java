package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * The state-space tree of one backtracking method, as the run explored it. Each call is a node;
 * choices the check rejected before calling are nodes too ("rejected"). Every node says from which
 * step it exists, until which step its call was running, and from which step its outcome is known,
 * so the browser can draw the tree as it was at any step.
 *
 * @param method          the recursive method
 * @param problem         "n-queens", "permutations", "subsets", "graph-coloring" or "backtracking"
 * @param kind            "loop" (one branch per choice) or "binary" (take / leave an element)
 * @param depthVariable   what a level of the tree stands for (row, v, i), or null
 * @param choiceVariable  what a branch chooses (col, c, i), or null for "binary"
 * @param assignment      the array the choices are written to (board, color), or null
 * @param assignmentShape "matrix" (board[depth][choice]), "matrix-transposed" (board[choice][depth]) or "vector"
 *                        (queens[depth] = choice), or null
 * @param depthBound      n, the depth of the full tree (from the goal test), or null if unknown
 * @param branching       choices per level (from the loop's range; 2 for take/leave), or null
 * @param nodes           in creation order; a node's parent always comes before it
 * @param truncated       more calls happened than are listed
 */
public record SearchTree(String method, String problem, String kind, String depthVariable, String choiceVariable,
                         String assignment, String assignmentShape, Long depthBound, Long branching,
                         List<SearchNode> nodes, boolean truncated) {
}
