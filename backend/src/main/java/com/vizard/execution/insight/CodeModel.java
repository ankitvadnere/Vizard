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
 */
public record CodeModel(
        Map<Integer, List<ConditionSite>> conditions,
        List<LoopSite> loops,
        Map<Integer, List<ArrayAccessSite>> accesses,
        List<MethodSite> methods,
        List<GraphSpec> graphs
) {

    public CodeModel(Map<Integer, List<ConditionSite>> conditions, List<LoopSite> loops,
                     Map<Integer, List<ArrayAccessSite>> accesses, List<MethodSite> methods) {
        this(conditions, loops, accesses, methods, List.of());
    }

    public static CodeModel empty() {
        return new CodeModel(Map.of(), List.of(), Map.of(), List.of(), List.of());
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
}
