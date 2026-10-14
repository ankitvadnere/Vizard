package com.vizard.execution.insight;

import com.github.javaparser.StaticJavaParser;
import com.vizard.execution.insight.CodeModel.SearchSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The choose → check → recurse → undo shape, the problem it solves, and recursion that is not a search. */
class BacktrackModelBuilderTest {

    private static List<SearchSpec> specs(String methods) {
        return BacktrackModelBuilder.build(StaticJavaParser.parse("import java.util.*;\nclass Main {\n" + methods + "\n}"));
    }

    private static SearchSpec only(String methods) {
        List<SearchSpec> found = specs(methods);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    void nQueensOnABoardRowByRow() {
        SearchSpec s = only("""
                static int N = 4;
                static boolean isSafe(int[][] b, int r, int c) { return true; }
                static boolean solve(int[][] board, int row) {
                    if (row == N) {
                        return true;
                    }
                    for (int col = 0; col < N; col++) {
                        if (isSafe(board, row, col)) {
                            board[row][col] = 1;
                            if (solve(board, row + 1)) {
                                return true;
                            }
                            board[row][col] = 0;
                        }
                    }
                    return false;
                }
                """);
        assertThat(s.method()).isEqualTo("solve");
        assertThat(s.kind()).isEqualTo("loop");
        assertThat(s.problem()).isEqualTo("n-queens");
        assertThat(s.depthParam()).isEqualTo("row");
        assertThat(s.choiceVar()).isEqualTo("col");
        assertThat(s.goalLine()).isEqualTo(6);
        assertThat(s.goalBound().text()).isEqualTo("N");
        assertThat(s.rejectIfFalse()).containsExactly(10);
        assertThat(s.callLines()).containsExactly(12);
        assertThat(s.undoLines()).containsExactly(15);
        assertThat(s.assignment()).isEqualTo("board");
        assertThat(s.assignmentShape()).isEqualTo("matrix");
    }

    @Test
    void nQueensVariants() {
        assertThat(only("""
                static boolean solve(int[][] board, int col) {
                    if (col >= 4) return true;
                    for (int i = 0; i < 4; i++) {
                        if (ok(board, i, col)) {
                            board[i][col] = 1;
                            if (solve(board, col + 1)) return true;
                            board[i][col] = 0;
                        }
                    }
                    return false;
                }
                static boolean ok(int[][] b, int r, int c) { return true; }
                """).assignmentShape()).isEqualTo("matrix-transposed");
        assertThat(only("""
                static int n = 4, count = 0;
                static boolean[] cols = new boolean[4], d1 = new boolean[8], d2 = new boolean[8];
                static void place(int row) {
                    if (row == n) { count++; return; }
                    for (int c = 0; c < n; c++) {
                        if (cols[c] || d1[row + c] || d2[row - c + n]) continue;
                        cols[c] = d1[row + c] = d2[row - c + n] = true;
                        place(row + 1);
                        cols[c] = d1[row + c] = d2[row - c + n] = false;
                    }
                }
                """).problem()).isEqualTo("n-queens");
        SearchSpec oneD = only("""
                static boolean safe(int[] q, int row, int col) {
                    for (int r = 0; r < row; r++) if (q[r] == col || Math.abs(q[r] - col) == row - r) return false;
                    return true;
                }
                static void place(int[] q, int row, int n) {
                    if (row == n) return;
                    for (int col = 0; col < n; col++) {
                        if (safe(q, row, col)) {
                            q[row] = col;
                            place(q, row + 1, n);
                        }
                    }
                }
                """);
        assertThat(oneD.problem()).isEqualTo("n-queens");
        assertThat(oneD.assignmentShape()).isEqualTo("vector");
        assertThat(oneD.undoLines()).isEmpty(); // overwritten by the next choice
    }

    @Test
    void permutationsThreeWays() {
        assertThat(only("""
                static void permute(int[] nums, boolean[] used, List<Integer> path) {
                    if (path.size() == nums.length) { System.out.println(path); return; }
                    for (int i = 0; i < nums.length; i++) {
                        if (!used[i]) {
                            used[i] = true;
                            path.add(nums[i]);
                            permute(nums, used, path);
                            path.remove(path.size() - 1);
                            used[i] = false;
                        }
                    }
                }
                """).problem()).isEqualTo("permutations");
        assertThat(only("""
                static void perm(int[] a, int k) {
                    if (k == a.length) return;
                    for (int i = k; i < a.length; i++) {
                        swap(a, k, i);
                        perm(a, k + 1);
                        swap(a, k, i);
                    }
                }
                static void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
                """).problem()).isEqualTo("permutations");
        assertThat(only("""
                static void perm(List<Integer> items, List<Integer> cur) {
                    if (cur.size() == items.size()) return;
                    for (int i = 0; i < items.size(); i++) {
                        if (cur.contains(items.get(i))) continue;
                        cur.add(items.get(i));
                        perm(items, cur);
                        cur.remove(cur.size() - 1);
                    }
                }
                """).problem()).isEqualTo("permutations");
    }

    @Test
    void subsetsTakeOrLeaveAndCombinations() {
        SearchSpec binary = only("""
                static void sub(int[] a, int i, int sum, int target) {
                    if (sum > target) return;
                    if (i == a.length) return;
                    sub(a, i + 1, sum + a[i], target);
                    sub(a, i + 1, sum, target);
                }
                """);
        assertThat(binary.kind()).isEqualTo("binary");
        assertThat(binary.problem()).isEqualTo("subsets");
        assertThat(binary.cutLines()).containsExactly(4);
        assertThat(binary.item().text()).isEqualTo("a[i]");
        assertThat(binary.includeLine()).isEqualTo(6);
        SearchSpec combos = only("""
                static List<List<Integer>> out = new ArrayList<>();
                static void combos(int[] a, int start, List<Integer> path) {
                    out.add(new ArrayList<>(path));
                    for (int i = start; i < a.length; i++) {
                        path.add(a[i]);
                        combos(a, i + 1, path);
                        path.remove(path.size() - 1);
                    }
                }
                """);
        assertThat(combos.problem()).isEqualTo("subsets");
        assertThat(combos.depthParam()).isEqualTo("start");
        assertThat(combos.stateNames()).containsExactly("path");
    }

    @Test
    void graphColouringChecksTheVertexsNeighbours() {
        SearchSpec s = only("""
                static boolean isSafe(int v, int[][] graph, int[] color, int c) {
                    for (int i = 0; i < 4; i++) if (graph[v][i] == 1 && color[i] == c) return false;
                    return true;
                }
                static boolean colour(int[][] graph, int m, int[] color, int v) {
                    if (v == 4) return true;
                    for (int c = 1; c <= m; c++) {
                        if (isSafe(v, graph, color, c)) {
                            color[v] = c;
                            if (colour(graph, m, color, v + 1)) return true;
                            color[v] = 0;
                        }
                    }
                    return false;
                }
                """);
        assertThat(s.problem()).isEqualTo("graph-coloring");
        assertThat(s.inclusive()).isTrue();
        assertThat(s.assignment()).isEqualTo("color");
    }

    @Test
    void otherRecursionIsNotASearch() {
        assertThat(specs("""
                static int fib(int n) { if (n <= 1) return n; return fib(n - 1) + fib(n - 2); }
                static void hanoi(int n, char a, char b, char c) {
                    if (n == 0) return;
                    hanoi(n - 1, a, c, b);
                    hanoi(n - 1, b, a, c);
                }
                static int paths(int r, int c, int n) {
                    if (r == n - 1 && c == n - 1) return 1;
                    if (r >= n || c >= n) return 0;
                    return paths(r + 1, c, n) + paths(r, c + 1, n);
                }
                static void mergeSort(int[] a, int lo, int hi) {
                    if (lo >= hi) return;
                    int mid = (lo + hi) / 2;
                    mergeSort(a, lo, mid);
                    mergeSort(a, mid + 1, hi);
                }
                static void printAll(int[] a, int i) {
                    if (i == a.length) return;
                    System.out.println(a[i]);
                    printAll(a, i + 1);
                }
                static int search(int[] a, int lo, int hi, int x) {
                    if (lo > hi) return -1;
                    int mid = (lo + hi) / 2;
                    if (a[mid] == x) return mid;
                    return a[mid] < x ? search(a, mid + 1, hi, x) : search(a, lo, mid - 1, x);
                }
                static void dfs(List<List<Integer>> adj, int u, boolean[] seen) {
                    seen[u] = true;
                    for (int v : adj.get(u)) if (!seen[v]) dfs(adj, v, seen);
                }
                """)).isEmpty();
    }
}
