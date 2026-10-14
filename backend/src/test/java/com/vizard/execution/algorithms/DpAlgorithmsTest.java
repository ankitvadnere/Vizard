package com.vizard.execution.algorithms;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DP recognisers, the complexity they report, and recursion that stays what it was. */
class DpAlgorithmsTest {

    private static List<Detection> detect(String methods) {
        return AlgorithmDetector.detect(StaticJavaParser.parse("import java.util.*;\nclass Main {\n" + methods + "\n}"));
    }

    private static final String FIB_THREE_WAYS = """
            static int naive(int n) { if (n <= 1) return n; return naive(n - 1) + naive(n - 2); }
            static int memo(int n, int[] m) {
                if (n <= 1) return n;
                if (m[n] != -1) return m[n];
                m[n] = memo(n - 1, m) + memo(n - 2, m);
                return m[n];
            }
            static int table(int n) {
                int[] dp = new int[n + 1];
                dp[1] = 1;
                for (int i = 2; i <= n; i++) dp[i] = dp[i - 1] + dp[i - 2];
                return dp[n];
            }
            """;

    @Test
    void fibonacciThreeWays() {
        List<Detection> found = detect(FIB_THREE_WAYS);
        assertThat(found).extracting(Detection::algorithm)
                .containsExactly(Algorithm.FIBONACCI_NAIVE, Algorithm.FIBONACCI_MEMO, Algorithm.FIBONACCI_TABLE);
        var naive = AlgorithmCatalog.describe(found.get(0), null,
                StructureFacts.NONE.withMethods(Map.of("naive", Map.of("n", 6L, "calls", 25L))));
        assertThat(naive.complexity().worst()).isEqualTo("O(2ⁿ)");
        assertThat(naive.measures()).anySatisfy(m -> {
            assertThat(m.label()).isEqualTo("2·F(n + 1) − 1");
            assertThat(m.value()).isEqualTo("25");
        });
        assertThat(AlgorithmCatalog.describe(found.get(1), null).complexity().worst()).isEqualTo("O(n)");
        assertThat(AlgorithmCatalog.describe(found.get(2), null).complexity().worst()).isEqualTo("O(n)");
    }

    @Test
    void memoisedKnapsackIsDpWhilePlainTakeOrLeaveStaysASearch() {
        List<Detection> found = detect("""
                static int best(int[] wt, int[] val, int i, int w, int[][] memo) {
                    if (i == wt.length || w == 0) return 0;
                    if (memo[i][w] != -1) return memo[i][w];
                    int r = best(wt, val, i + 1, w, memo);
                    if (wt[i] <= w) r = Math.max(r, val[i] + best(wt, val, i + 1, w - wt[i], memo));
                    memo[i][w] = r;
                    return r;
                }
                static int plain(int[] wt, int[] val, int i, int w) {
                    if (i == wt.length) return 0;
                    int skip = plain(wt, val, i + 1, w);
                    if (wt[i] > w) return skip;
                    return Math.max(skip, val[i] + plain(wt, val, i + 1, w - wt[i]));
                }
                """);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.KNAPSACK, Algorithm.SUBSETS);
        assertThat(found.get(0).traits()).contains(DpAlgorithms.MEMO);
        assertThat(AlgorithmCatalog.describe(found.get(0), null).complexity().worst()).isEqualTo("O(n·W)");
    }

    @Test
    void tableProblemsAndTheirComplexity() {
        List<Detection> found = detect("""
                static int edit(String a, String b) {
                    int[][] dp = new int[a.length() + 1][b.length() + 1];
                    for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
                    for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
                    for (int i = 1; i <= a.length(); i++)
                        for (int j = 1; j <= b.length(); j++)
                            dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1) ? dp[i - 1][j - 1]
                                    : 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                    return dp[a.length()][b.length()];
                }
                static int chain(int[] d) {
                    int n = d.length - 1;
                    int[][] dp = new int[n + 1][n + 1];
                    for (int len = 2; len <= n; len++)
                        for (int i = 1; i <= n - len + 1; i++) {
                            int j = i + len - 1;
                            dp[i][j] = Integer.MAX_VALUE;
                            for (int k = i; k < j; k++) {
                                int cost = dp[i][k] + dp[k + 1][j] + d[i - 1] * d[k] * d[j];
                                if (cost < dp[i][j]) dp[i][j] = cost;
                            }
                        }
                    return dp[1][n];
                }
                static int paths(int r, int c) {
                    int[][] dp = new int[r][c];
                    for (int i = 0; i < r; i++)
                        for (int j = 0; j < c; j++)
                            dp[i][j] = i == 0 || j == 0 ? 1 : dp[i - 1][j] + dp[i][j - 1];
                    return dp[r - 1][c - 1];
                }
                """);
        assertThat(found).extracting(Detection::algorithm)
                .containsExactly(Algorithm.EDIT_DISTANCE, Algorithm.MATRIX_CHAIN, Algorithm.DP_TABLE);
        assertThat(AlgorithmCatalog.describe(found.get(1), null).complexity().worst()).isEqualTo("O(n³)");
        assertThat(AlgorithmCatalog.describe(found.get(2), null).complexity().worst()).isEqualTo("O(n·m)");
    }

    @Test
    void otherRecursionIsNotClaimed() {
        assertThat(detect("""
                static void hanoi(int n, char a, char b, char c) {
                    if (n == 0) return;
                    hanoi(n - 1, a, c, b);
                    hanoi(n - 1, b, a, c);
                }
                static int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                """)).isEmpty();
    }
}
