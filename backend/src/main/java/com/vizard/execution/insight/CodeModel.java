package com.vizard.execution.insight;

import java.util.List;
import java.util.Map;

/**
 * Line-level facts about the program, extracted once from the AST before tracing.
 *
 * @param conditions  conditions keyed by the line where they are evaluated
 * @param loops       every loop in the file
 * @param accesses    array accesses keyed by line
 * @param methods     methods and constructors with their line ranges and pointer variables
 * @param graphs      graph traversals: one per loop over a vertex's neighbours
 * @param searches    backtracking searches: one per recursive method with the choose → recurse → undo shape
 * @param tables      dynamic-programming tables (dp[] filled bottom-up, or a memo[] filled top-down)
 * @param recursions  recursive methods drawn as recursion trees: memoised ones and plain overlapping recursion
 * @param bnbs        branch and bound with a live-node list of node objects
 */
public record CodeModel(
        Map<Integer, List<ConditionSite>> conditions,
        List<LoopSite> loops,
        Map<Integer, List<ArrayAccessSite>> accesses,
        List<MethodSite> methods,
        List<GraphSpec> graphs,
        List<SearchSpec> searches,
        List<TableSpec> tables,
        List<RecursionSpec> recursions,
        List<BnbSpec> bnbs
) {

    public CodeModel(Map<Integer, List<ConditionSite>> conditions, List<LoopSite> loops,
                     Map<Integer, List<ArrayAccessSite>> accesses, List<MethodSite> methods) {
        this(conditions, loops, accesses, methods, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static CodeModel empty() {
        return new CodeModel(Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    /**
     * A condition. For loops the "branch" is the body; for if statements it is the then-part.
     *
     * @param kind        "if", "while", "for" or "do"
     * @param line        line where execution stops to evaluate it (the for header for for-loops)
     * @param branchStart first line of the code that runs when it is true
     * @param branchEnd   last line of that code
     */
    public record ConditionSite(String kind, int line, Expr expression, int branchStart, int branchEnd) {

        /** True if the branch starts on another line, so control flow alone reveals the result. */
        boolean branchOnOtherLines() {
            return line < branchStart || line > branchEnd;
        }

        boolean branchContains(int otherLine) {
            return otherLine >= branchStart && otherLine <= branchEnd;
        }
    }

    /**
     * @param kind          "for", "for-each", "while" or "do"
     * @param headerLine    line of the for/while keyword, or of "do"
     * @param conditionLine line where the condition is checked (differs from headerLine only for do-while)
     * @param bodyStart     first line of the body
     * @param bodyEnd       last line of the body
     * @param endLine       last line of the whole statement
     * @param variable      loop variable of for/for-each loops, else null
     */
    public record LoopSite(String kind, int headerLine, int conditionLine, int bodyStart, int bodyEnd,
                           int endLine, String variable) {

        boolean contains(int line) {
            return line >= headerLine && line <= endLine;
        }

        boolean bodyContains(int line) {
            return line >= bodyStart && line <= bodyEnd;
        }

        /** Loops written on one line can't be followed iteration by iteration through line changes. */
        boolean onOneLine() {
            return bodyStart == headerLine && bodyEnd == headerLine;
        }
    }

    /**
     * @param array       the array part, e.g. Name("arr")
     * @param index       the index part, e.g. j + 1
     * @param write       the element is assigned (arr[i] = ..., arr[i]++)
     * @param inCondition the access is part of an if/loop condition
     */
    public record ArrayAccessSite(int line, Expr array, Expr index, String text, boolean write, boolean inCondition) {
    }

    /** An array expression and the int variables used to index it inside one method. */
    public record PointerSpec(Expr array, List<String> variables) {
    }

    /** {@code from..to} bounds the part of {@code array} a method works on (low..high, left..right). */
    public record RangeSpec(Expr array, String from, String to) {
    }

    public record MethodSite(String name, int startLine, int endLine, List<PointerSpec> pointers,
                             List<RangeSpec> ranges) {

        boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }
    }

    /**
     * A loop over the neighbours of a vertex, and the state the traversal around it keeps.
     * Found by {@link GraphModelBuilder} from the code's structure; variable names don't matter.
     *
     * @param method        method containing the loop
     * @param graph         the graph variable: adj, graph, this.adj
     * @param shape         "list": for-each over the neighbours (adj.get(u), adj[u], map.get(u));
     *                      "matrix": index loop over a row, testing g[u][v]
     * @param vertex        the vertex whose neighbours are scanned (u)
     * @param neighbor      the loop variable (v, or an edge e)
     * @param loopLine      line of the neighbour loop's header
     * @param bodyStart     first line of its body
     * @param bodyEnd       last line of its body
     * @param target        how to get the neighbour vertex from the loop variable: "" (itself),
     *                      "[0]" (an int[] edge), ".to" (an Edge object)
     * @param weight        how to get the edge weight, same notation; for a matrix "cell"; null if unweighted
     * @param visited       visited array or set, or null
     * @param dist          distance array or map, or null
     * @param parent        parent / predecessor array or map, or null
     * @param frontier      the queue, stack or priority queue holding vertices still to process, or null
     * @param frontierVertex how to get the vertex from a frontier element ("", "[1]", ".v"), or null if unknown
     * @param frontierOrder "queue", "stack" or "priority", or null
     * @param recursive     the method calls itself with the neighbour (recursive DFS)
     * @param relaxes       {@code if (dist[u] + w < dist[v]) dist[v] = dist[u] + w}: an edge relaxation
     * @param levels        {@code dist[v] = dist[u] + 1}: BFS levels
     * @param minScan       the next vertex is picked by scanning for the smallest distance (array Dijkstra)
     */
    public record GraphSpec(String method, int methodStart, int methodEnd, Expr graph, String shape,
                            String vertex, String neighbor, int loopLine, int bodyStart, int bodyEnd,
                            String target, String weight, String visited, String dist, String parent,
                            String frontier, String frontierVertex, String frontierOrder,
                            boolean recursive, boolean relaxes, boolean levels, boolean minScan) {

        boolean bodyContains(int line) {
            return line >= bodyStart && line <= bodyEnd;
        }

        boolean methodContains(int line) {
            return line >= methodStart && line <= methodEnd;
        }
    }

    /**
     * A backtracking search, found by {@link BacktrackModelBuilder}: a recursive method that makes a
     * choice, recurses, and undoes the choice. Each call is a node of the state-space tree.
     *
     * @param kind           "loop": one call per choice, inside a for loop over the choices;
     *                       "binary": two calls, include / exclude (no loop)
     * @param problem        "n-queens", "permutations", "subsets", "graph-coloring" or "backtracking"
     * @param depthParam     the parameter that grows by one per level (row, k, i), or null (e.g. path.size())
     * @param goalLine       line of the goal test ({@code if (row == n) ...}), 0 if none
     * @param goalBound      the side of the goal test that gives the depth of the tree (n), or null
     * @param cutLines       bound checks that end a call early ({@code if (sum > target) return;})
     * @param loopLine       header line of the choice loop, 0 for "binary"
     * @param choiceVar      the loop variable (col, c, i), null for "binary"
     * @param choiceFrom     its first value
     * @param choiceTo       its bound, {@code inclusive} for {@code <=}
     * @param rejectIfFalse  lines of ifs around a recursive call: false = that choice is rejected (pruned)
     * @param rejectIfTrue   lines of {@code if (...) continue;} before the call: true = choice rejected
     * @param callLines      lines with a recursive call
     * @param item           the element a branch stands for: {@code a[i]} for take/leave, {@code nums[i]} in
     *                       {@code path.add(nums[i])}; null if the choice is just the loop variable
     * @param includeLine    "binary": line of the call that includes the item
     * @param undoLines      statements after a call that undo the choice
     * @param assignment     the array the choice is written to (board, queens, color), or null
     * @param assignmentShape "matrix" (board[depth][choice] = 1), "matrix-transposed" (board[choice][depth]),
     *                       "vector" (color[v] = c), or null
     * @param stateNames     variables the choices change and undo (board, path, used): changes to them are
     *                       the search moving, not a solution being recorded
     * @param bestVar        branch and bound: the best value found so far ({@code best}), which the checks
     *                       compare a branch against and the goal updates; null for plain backtracking
     */
    public record SearchSpec(String method, int methodStart, int methodEnd, String kind, String problem,
                             String depthParam, int goalLine, Expr goalBound, List<Integer> cutLines,
                             int loopLine, String choiceVar, Expr choiceFrom, Expr choiceTo, boolean inclusive,
                             List<Integer> rejectIfFalse, List<Integer> rejectIfTrue, List<Integer> callLines,
                             Expr item, int includeLine, List<Integer> undoLines, String assignment,
                             String assignmentShape, List<String> stateNames, String bestVar) {

        boolean methodContains(int line) {
            return line >= methodStart && line <= methodEnd;
        }
    }

    /**
     * A dynamic-programming table, found by {@link DpModelBuilder}.
     *
     * @param kind     "tabulation" (filled by loops from earlier cells) or "memo" (filled by a recursive method)
     * @param problem  "fibonacci", "knapsack", "lcs", "edit-distance", "coin-change", "matrix-chain" or "dp"
     * @param table    the array: dp, memo
     * @param dims     1 or 2
     * @param sites    lines that read or write the table, with what to show there
     * @param rowVar   the variable indexing rows (i), or null; for a 1-D table null
     * @param colVar   the variable indexing columns (w, j; the only index of a 1-D table), or null
     * @param rowString string whose letters label the rows (LCS: a), with {@code rowOffset} (charAt(i - 1) → 1)
     * @param colString string whose letters label the columns, with {@code colOffset}
     * @param loopDepth loops around the recurrence (work per cell = the innermost extra loop, e.g. k)
     */
    public record TableSpec(String method, int methodStart, int methodEnd, String kind, String problem, String table,
                            int dims, List<TableSite> sites, String rowVar, String colVar, String rowString,
                            int rowOffset, String colString, int colOffset, int loopDepth) {

        boolean methodContains(int line) {
            return line >= methodStart && line <= methodEnd;
        }
    }

    /**
     * A line that uses the table.
     *
     * @param cell   the cell this line is working on: what it writes, or (for a read-only line such as
     *               {@code int cost = dp[i][k] + ...}) the cell the recurrence is computing
     * @param write  the line assigns the cell
     * @param label  what is computed: "dp[i][w]", "cost", or "check" for a condition
     * @param shown  the expression as written: the right-hand side, initializer or condition
     * @param reads  reads of the table in it (dp[i - 1][w]...)
     * @param values other array elements in it to show with their values (val[i - 1], wt[i - 1])
     * @param names  plain variables in it to show with their values (cost, a, j)
     */
    public record TableSite(int line, Expr cell, boolean write, String label, String shown, List<Expr> reads,
                            List<Expr> values, List<String> names) {
    }

    /**
     * A recursive method drawn as a recursion tree.
     *
     * @param memo       the memo table, or null for plain recursion (then repeated calls are wasted work)
     * @param checkStart first line of the memo check ({@code if (memo[n] != -1) return memo[n];}), 0 if none
     * @param checkEnd   last line of it
     * @param problem    "fibonacci", "knapsack", "lcs", "edit-distance", "coin-change" or "dp"
     */
    public record RecursionSpec(String method, int methodStart, int methodEnd, String memo, int checkStart,
                                int checkEnd, String problem) {

        boolean methodContains(int line) {
            return line >= methodStart && line <= methodEnd;
        }
    }

    /**
     * Branch and bound with a live-node list, found by {@link BnbModelBuilder}: node objects wait in a
     * queue, stack or priority queue; each one taken out is expanded into children, and a node whose
     * bound cannot beat the best solution so far is dropped.
     *
     * @param frontier   the live-node list (pq, queue)
     * @param order      "priority" (best-first / least-cost), "queue" (FIFO) or "stack" (LIFO)
     * @param nodeClass  the node class (Node)
     * @param current    the variable holding the node being expanded (u)
     * @param boundField the node field compared with the best (bound)
     * @param bestVar    the best value so far (best, maxProfit)
     * @param valueField the node field the best is taken from (profit, cost), or null
     * @param levelField the node field counting levels (level), or null
     * @param maximise   the best is a maximum (bounds are upper bounds)
     * @param problem    "knapsack" (a fractional bound) or "branch-and-bound"
     * @param pruneLines lines whose condition being true discards the current node ({@code if (u.bound <= best) continue;})
     * @param addGuards  lines whose condition being false drops a new node instead of adding it, with that node's variable
     */
    public record BnbSpec(String method, int methodStart, int methodEnd, String frontier, String order,
                          String nodeClass, String current, String boundField, String bestVar, String valueField,
                          String levelField, boolean maximise, String problem, List<Integer> pruneLines,
                          Map<Integer, String> addGuards) {

        boolean methodContains(int line) {
            return line >= methodStart && line <= methodEnd;
        }
    }
}
