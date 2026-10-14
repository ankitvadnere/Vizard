package com.vizard.execution.algorithms;

/**
 * Sizes of the structures this run built and searched, measured from the trace, for complexities
 * that depend on them (a BST operation is O(h), so its real height matters; BFS is O(V + E), so V
 * and E are shown; a backtracking search is compared with the size of its full state-space tree).
 *
 * @param treeNodes     nodes in the largest tree seen (0 if none)
 * @param treeHeight    its height in edges (a single node has height 0; -1 if no tree)
 * @param listNodes     nodes in the longest linked list seen (0 if none)
 * @param graphVertices vertices V of the largest graph seen (0 if none)
 * @param graphEdges    its edges E (an undirected edge u–v counts once)
 * @param graphDirected whether that graph is directed
 * @param searchDepth   n: levels of the full state-space tree, from the goal test (null if unknown)
 * @param searchBranching choices per level (null if unknown)
 * @param nodesExplored calls the search made (0 if no search)
 */
public record StructureFacts(int treeNodes, int treeHeight, int listNodes, int graphVertices, int graphEdges,
                             boolean graphDirected, Long searchDepth, Long searchBranching, int nodesExplored) {

    public static final StructureFacts NONE = new StructureFacts(0, -1, 0);

    public StructureFacts(int treeNodes, int treeHeight, int listNodes) {
        this(treeNodes, treeHeight, listNodes, 0, 0, false, null, null, 0);
    }

    public StructureFacts withGraph(int vertices, int edges, boolean directed) {
        return new StructureFacts(treeNodes, treeHeight, listNodes, vertices, edges, directed, searchDepth,
                searchBranching, nodesExplored);
    }

    public StructureFacts withSearch(Long depth, Long branching, int explored) {
        return new StructureFacts(treeNodes, treeHeight, listNodes, graphVertices, graphEdges, graphDirected, depth,
                branching, explored);
    }

    public boolean hasGraph() {
        return graphVertices > 0;
    }
}
