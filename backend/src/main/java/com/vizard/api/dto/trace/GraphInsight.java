package com.vizard.api.dto.trace;

import java.util.List;

/**
 * A graph and the state of the traversal over it at one step, read from the heap through the
 * variables the AST identified (see {@code GraphModelBuilder}). Everything here is a fact of the
 * recorded state; the browser only draws it.
 *
 * @param name           the graph variable as written: adj, graph
 * @param ref            heap id of the graph object
 * @param representation "adjacency-list", "adjacency-map" or "adjacency-matrix"
 * @param directed       false when every edge u → v has a matching v → u (then each edge is listed once)
 * @param weighted       edges carry weights
 * @param vertices       vertex labels in their natural order (0..n-1, or the map's keys)
 * @param edges          the edges present now (a graph being built grows step by step)
 * @param current        the vertex being processed (u), or null
 * @param examining      the edge u → v whose other end is being looked at right now, or null
 * @param visited        vertices marked visited / finished
 * @param frontier       vertices waiting in the queue, stack or priority queue, in its order
 * @param frontierName   that collection's variable, e.g. queue
 * @param frontierOrder  "queue", "stack" or "priority"
 * @param onStack        recursive DFS: the vertices of the active calls, outermost first
 * @param order          vertices in the order they were first visited
 * @param treeEdges      edges of the BFS/DFS/shortest-path tree found so far (parent → child)
 * @param distances      the distance array or map, one entry per vertex ("∞" when unreached)
 * @param distName       its variable, e.g. dist
 * @param truncated      the graph was too large to read completely
 */
public record GraphInsight(
        String name,
        long ref,
        String representation,
        boolean directed,
        boolean weighted,
        List<String> vertices,
        List<GraphEdge> edges,
        String current,
        GraphEdge examining,
        List<String> visited,
        List<String> frontier,
        String frontierName,
        String frontierOrder,
        List<String> onStack,
        List<String> order,
        List<GraphEdge> treeEdges,
        List<VertexValue> distances,
        String distName,
        boolean truncated
) {
}
