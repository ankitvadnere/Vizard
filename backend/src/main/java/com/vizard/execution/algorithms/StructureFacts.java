package com.vizard.execution.algorithms;

/**
 * Sizes of the linked structures this run built, measured from the trace, for complexities that
 * depend on them (a BST operation is O(h), so its real height matters).
 *
 * @param treeNodes  nodes in the largest tree seen (0 if none)
 * @param treeHeight its height in edges (a single node has height 0; -1 if no tree)
 * @param listNodes  nodes in the longest linked list seen (0 if none)
 */
public record StructureFacts(int treeNodes, int treeHeight, int listNodes) {

    public static final StructureFacts NONE = new StructureFacts(0, -1, 0);
}
