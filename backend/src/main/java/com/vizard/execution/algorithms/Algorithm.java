package com.vizard.execution.algorithms;

/** Algorithms Vizard can recognise by structure, with their display names. */
public enum Algorithm {
    BUBBLE_SORT("bubble-sort", "Bubble sort", "Sorting"),
    SELECTION_SORT("selection-sort", "Selection sort", "Sorting"),
    INSERTION_SORT("insertion-sort", "Insertion sort", "Sorting"),
    MERGE_SORT("merge-sort", "Merge sort", "Sorting"),
    QUICK_SORT("quick-sort", "Quick sort", "Sorting"),
    LINEAR_SEARCH("linear-search", "Linear search", "Searching"),
    BINARY_SEARCH("binary-search", "Binary search", "Searching"),
    BST_SEARCH("bst-search", "BST search", "Trees"),
    BST_INSERT("bst-insert", "BST insertion", "Trees"),
    BST_DELETE("bst-delete", "BST deletion", "Trees"),
    PREORDER("preorder", "Preorder traversal", "Trees"),
    INORDER("inorder", "Inorder traversal", "Trees"),
    POSTORDER("postorder", "Postorder traversal", "Trees"),
    LEVEL_ORDER("level-order", "Level-order traversal (BFS)", "Trees"),
    LIST_REVERSAL("list-reversal", "Linked list reversal", "Linked lists"),
    BFS("bfs", "Breadth-first search (BFS)", "Graphs"),
    DFS("dfs", "Depth-first search (DFS)", "Graphs"),
    DIJKSTRA("dijkstra", "Dijkstra's shortest paths", "Graphs"),
    N_QUEENS("n-queens", "N-Queens (backtracking)", "Backtracking"),
    PERMUTATIONS("permutations", "Permutations (backtracking)", "Backtracking"),
    SUBSETS("subsets", "Subsets / subset sum (backtracking)", "Backtracking"),
    GRAPH_COLORING("graph-coloring", "Graph colouring (backtracking)", "Backtracking"),
    BACKTRACKING("backtracking", "Backtracking search", "Backtracking"),
    FIBONACCI_NAIVE("fibonacci-naive", "Fibonacci (plain recursion)", "Dynamic programming"),
    FIBONACCI_MEMO("fibonacci-memo", "Fibonacci (memoisation)", "Dynamic programming"),
    FIBONACCI_TABLE("fibonacci-table", "Fibonacci (tabulation)", "Dynamic programming"),
    KNAPSACK("knapsack", "0/1 knapsack (DP)", "Dynamic programming"),
    LCS("lcs", "Longest common subsequence", "Dynamic programming"),
    EDIT_DISTANCE("edit-distance", "Edit distance", "Dynamic programming"),
    COIN_CHANGE("coin-change", "Coin change (DP)", "Dynamic programming"),
    MATRIX_CHAIN("matrix-chain", "Matrix chain multiplication", "Dynamic programming"),
    DP_MEMO("dp-memo", "Dynamic programming (memoisation)", "Dynamic programming"),
    DP_TABLE("dp-table", "Dynamic programming (tabulation)", "Dynamic programming");

    private final String id;
    private final String displayName;
    private final String category;

    Algorithm(String id, String displayName, String category) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String category() {
        return category;
    }
}
