package com.vizard.execution.algorithms;

import com.vizard.api.dto.analysis.ComplexityInfo;
import com.vizard.api.dto.analysis.Measure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Complexity of the dynamic-programming algorithms: the number of subproblems (table cells, or
 * distinct memo entries) times the work per subproblem, with what this run measured beside it.
 */
record DpCosts(ComplexityInfo complexity, List<Measure> measures) {

    static DpCosts of(Detection d, Map<String, Long> f) {
        boolean memo = d.has(DpAlgorithms.MEMO);
        ComplexityInfo c;
        List<Measure> m = new ArrayList<>();
        switch (d.algorithm()) {
            case FIBONACCI_NAIVE -> {
                c = new ComplexityInfo("O(2ⁿ)", "O(2ⁿ)", "O(2ⁿ)", "O(n)", null,
                        "fib(n) calls fib(n − 1) and fib(n − 2), and neither remembers anything, so the number of "
                                + "calls is 2·F(n + 1) − 1: it grows like φⁿ ≈ 1.618ⁿ, exponential. Only n + 1 different "
                                + "subproblems exist; everything else is repeated work. The space is the recursion depth, n.");
                Long n = f.get("n");
                put(m, "n", n);
                put(m, "Calls made", f.get("calls"));
                if (n != null && n <= 40) {
                    m.add(new Measure("2·F(n + 1) − 1", Long.toString(2 * fib(n + 1) - 1)));
                }
                put(m, "Repeated calls (wasted)", f.get("repeats"));
                put(m, "Different subproblems", f.get("distinct"));
            }
            case FIBONACCI_MEMO -> {
                c = new ComplexityInfo("O(n)", "O(n)", "O(n)", "O(n)", null,
                        "Each fib(k) is computed once and then read from the memo, so there are n + 1 subproblems "
                                + "with O(1) work each, and at most 2n − 1 calls (every other call is a memo hit). "
                                + "Space: the memo array and the recursion depth, both O(n).");
                put(m, "n", f.get("n"));
                put(m, "Calls made", f.get("calls"));
                put(m, "Memo hits", f.get("hits"));
                put(m, "Cells stored", f.get("cells"));
            }
            case FIBONACCI_TABLE -> {
                c = new ComplexityInfo("O(n)", "O(n)", "O(n)", "O(n)", null,
                        "One pass from dp[2] to dp[n], each cell O(1) from the two before it; no recursion at all. "
                                + "Only the last two values are ever read, so two variables would make the space O(1).");
                putTable(m, f);
            }
            case KNAPSACK -> {
                c = new ComplexityInfo("O(n·W)", "O(n·W)", "O(n·W)", "O(n·W)", null,
                        "One subproblem per item count i and capacity w: (n + 1)(W + 1) of them, each O(1) "
                                + (memo ? "(a memo hit or two recursive calls)" : "(two cells of the row above)")
                                + ". W is a number, not an input length, so O(n·W) is pseudo-polynomial: doubling the "
                                + "number of digits of W squares the work.");
                if (memo) {
                    putMemo(m, f);
                } else {
                    putTable(m, f);
                }
            }
            case LCS -> {
                c = new ComplexityInfo("O(m·n)", "O(m·n)", "O(m·n)", "O(m·n)", null,
                        "One cell per pair of prefix lengths (i, j): (m + 1)(n + 1) cells, each from at most three "
                                + "neighbours in O(1). Keeping only two rows would cut the space to O(n).");
                putTableOrMemo(m, f, memo);
            }
            case EDIT_DISTANCE -> {
                c = new ComplexityInfo("O(m·n)", "O(m·n)", "O(m·n)", "O(m·n)", null,
                        "One cell per pair of prefix lengths: (m + 1)(n + 1) cells, each the minimum of three "
                                + "neighbours plus a cost, O(1).");
                putTableOrMemo(m, f, memo);
            }
            case COIN_CHANGE -> {
                c = new ComplexityInfo("O(A·k)", "O(A·k)", "O(A·k)", "O(A)", null,
                        "For each amount 1..A every one of the k coins is tried once: A·k steps. The table has one "
                                + "cell per amount, O(A). Like knapsack it is pseudo-polynomial in A.");
                putTableOrMemo(m, f, memo);
                put(m, "Cell updates", f.get("writes"));
            }
            case MATRIX_CHAIN -> {
                c = new ComplexityInfo("O(n³)", "O(n³)", "O(n³)", "O(n²)", null,
                        "There are about n²/2 chains A_i … A_j, and each tries every split point k between them: "
                                + "O(n) work per cell, O(n³) in total, for an n × n table.");
                putTable(m, f);
            }
            case DP_MEMO -> {
                c = new ComplexityInfo("O(S·w)", "O(S·w)", "O(S·w)", "O(S)", null,
                        "S different subproblems, each computed once and then answered from the memo, with w work "
                                + "per subproblem besides the recursive calls.");
                putMemo(m, f);
            }
            default -> {
                int loops = d.traits().stream().filter(t -> t.startsWith("loops-"))
                        .mapToInt(t -> Integer.parseInt(t.substring(6))).findFirst().orElse(1);
                String time = switch (loops) {
                    case 1 -> "O(n)";
                    case 2 -> "O(n·m)";
                    case 3 -> "O(n³)";
                    default -> "O(n^" + loops + ")";
                };
                c = new ComplexityInfo(time, time, time, "O(table)", null,
                        "Each cell is computed once from earlier cells; the " + loops + " loop"
                                + (loops == 1 ? "" : "s") + " around the recurrence give the number of steps "
                                + "(" + time + " if each loop runs up to n or m times). Space: the table.");
                putTable(m, f);
            }
        }
        return new DpCosts(c, m);
    }

    private static void putTableOrMemo(List<Measure> m, Map<String, Long> f, boolean memo) {
        if (memo) {
            putMemo(m, f);
        } else {
            putTable(m, f);
        }
    }

    private static void putTable(List<Measure> m, Map<String, Long> f) {
        Long rows = f.get("rows");
        Long cols = f.get("cols");
        if (rows != null && cols != null) {
            m.add(new Measure("Table", rows == 1 ? cols + " cells" : rows + " × " + cols + " = " + rows * cols + " cells"));
        }
        put(m, "Cells filled", f.get("cells"));
    }

    private static void putMemo(List<Measure> m, Map<String, Long> f) {
        put(m, "Calls made", f.get("calls"));
        put(m, "Memo hits", f.get("hits"));
        put(m, "Subproblems stored", f.get("cells"));
    }

    private static void put(List<Measure> m, String label, Long value) {
        if (value != null) {
            m.add(new Measure(label, value.toString()));
        }
    }

    static long fib(long n) {
        long a = 0;
        long b = 1;
        for (long i = 0; i < n; i++) {
            long t = a + b;
            a = b;
            b = t;
        }
        return a;
    }
}
