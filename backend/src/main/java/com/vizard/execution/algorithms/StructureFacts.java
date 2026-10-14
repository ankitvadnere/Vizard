package com.vizard.execution.algorithms;

/**
 * Sizes of the linked structures and graphs this run built, measured from the trace, for
 * complexities that depend on them (a BST operation is O(h), so its real height matters; BFS is
 * O(V + E), so V and E are shown).
 *
 * @param treeNodes     nodes in the largest tree seen (0 if none)
 * @param treeHeight    its height in edges (a single node has height 0; -1 if no tree)
 * @param listNodes     nodes in the longest linked list seen (0 if none)
 * @param graphVertices vertices V of the largest graph seen (0 if none)
 * @param graphEdges    its edges E (an undirected edge u–v counts once)
 * @param graphDirected whether that graph is directed
 */
public record StructureFacts(int treeNodes, int treeHeight, int listNodes, int graphVertices, int graphEdges,
                             boolean graphDirected) {

    public static final StructureFacts NONE = new StructureFacts(0, -1, 0);

    public StructureFacts(int treeNodes, int treeHeight, int listNodes) {
        this(treeNodes, treeHeight, listNodes, 0, 0, false);
    }

    public StructureFacts withGraph(int vertices, int edges, boolean directed) {
        return new StructureFacts(treeNodes, treeHeight, listNodes, vertices, edges, directed);
    }

    public boolean hasGraph() {
        return graphVertices > 0;
    }
}
