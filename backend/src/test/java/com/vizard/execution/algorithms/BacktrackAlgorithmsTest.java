package com.vizard.execution.algorithms;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Backtracking recognisers and the size of the full state-space tree each one is compared with. */
class BacktrackAlgorithmsTest {

    private static List<Detection> detect(String methods) {
        return AlgorithmDetector.detect(StaticJavaParser.parse("import java.util.*;\nclass Main {\n" + methods + "\n}"));
    }

    private static StructureFacts search(long depth, long branching, int explored) {
        return StructureFacts.NONE.withSearch(depth, branching, explored);
    }

    @Test
    void nQueensIsRecognisedAndItsHelperIsPartOfIt() {
        List<Detection> found = detect("""
                static int N = 4;
                static boolean isSafe(int[][] board, int row, int col) {
                    for (int i = 0; i < row; i++) {
                        if (board[i][col] == 1) return false;
                    }
                    return true;
                }
                static boolean solve(int[][] board, int row) {
                    if (row == N) return true;
                    for (int col = 0; col < N; col++) {
                        if (isSafe(board, row, col)) {
                            board[row][col] = 1;
                            if (solve(board, row + 1)) return true;
                            board[row][col] = 0;
                        }
                    }
                    return false;
                }
                """);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.N_QUEENS);
        var match = AlgorithmCatalog.describe(found.get(0), null, search(4, 4, 9));
        assertThat(match.complexity().worst()).isEqualTo("O(n!)");
        assertThat(match.complexity().space()).isEqualTo("O(n²)");
        assertThat(match.bound().metric()).isEqualTo("nodesExplored");
        assertThat(match.bound().value()).isEqualTo(341L); // 1 + 4 + 16 + 64 + 256
        assertThat(match.bound().size()).isEqualTo("n = 4");
    }

    @Test
    void fullTreeSizes() {
        assertThat(SearchCosts.powerSum(4, 4)).isEqualTo(341L);
        assertThat(SearchCosts.powerSum(3, 4)).isEqualTo(121L);
        assertThat(SearchCosts.permutationNodes(3)).isEqualTo(16L); // 1 + 3 + 6 + 6
        assertThat(SearchCosts.permutationNodes(4)).isEqualTo(65L);
        assertThat(SearchCosts.powerSum(10, 30)).isNull(); // too big to be useful
    }

    @Test
    void subsetsAndPermutations() {
        List<Detection> found = detect("""
                static void sub(int[] a, int i, int sum, int target, List<Integer> chosen) {
                    if (sum > target) return;
                    if (i == a.length) return;
                    chosen.add(a[i]);
                    sub(a, i + 1, sum + a[i], target, chosen);
                    chosen.remove(chosen.size() - 1);
                    sub(a, i + 1, sum, target, chosen);
                }
                static void permute(int[] nums, boolean[] used, List<Integer> path) {
                    if (path.size() == nums.length) return;
                    for (int i = 0; i < nums.length; i++) {
                        if (!used[i]) {
                            used[i] = true; path.add(nums[i]);
                            permute(nums, used, path);
                            path.remove(path.size() - 1); used[i] = false;
                        }
                    }
                }
                """);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.SUBSETS, Algorithm.PERMUTATIONS);
        assertThat(AlgorithmCatalog.describe(found.get(0), null, search(4, 2, 27)).bound().value()).isEqualTo(31L);
        var perms = AlgorithmCatalog.describe(found.get(1), null, search(3, 3, 16));
        assertThat(perms.complexity().worst()).isEqualTo("O(n·n!)");
        assertThat(perms.bound().value()).isEqualTo(16L);
    }

    @Test
    void graphColouringAndAGenericSearch() {
        List<Detection> found = detect("""
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
                static void words(char[] out, int pos, String letters) {
                    if (pos == out.length) { System.out.println(new String(out)); return; }
                    for (int i = 0; i < letters.length(); i++) {
                        out[pos] = letters.charAt(i);
                        words(out, pos + 1, letters);
                    }
                }
                """);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.GRAPH_COLORING, Algorithm.BACKTRACKING);
        var colouring = AlgorithmCatalog.describe(found.get(0), null, search(4, 3, 5));
        assertThat(colouring.complexity().worst()).isEqualTo("O(mⁿ)");
        assertThat(colouring.bound().value()).isEqualTo(121L);
        assertThat(AlgorithmCatalog.describe(found.get(1), null).complexity().worst()).isEqualTo("O(bᵈ)");
    }
}
