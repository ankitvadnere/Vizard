package com.vizard.execution.insight;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.vizard.execution.insight.CodeModel.TableSite;
import com.vizard.execution.insight.CodeModel.TableSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DP tables, memoisation and plain overlapping recursion found by structure; arrays that are not DP tables. */
class DpModelBuilderTest {

    private static CompilationUnit parse(String methods) {
        return StaticJavaParser.parse("import java.util.*;\nclass Main {\n" + methods + "\n}");
    }

    private static List<TableSpec> tables(String methods) {
        return DpModelBuilder.tables(parse(methods));
    }

    @Test
    void knapsackTableWithItsRecurrence() {
        List<TableSpec> found = tables("""
                static int knapsack(int[] wt, int[] val, int W) {
                    int n = wt.length;
                    int[][] dp = new int[n + 1][W + 1];
                    for (int i = 1; i <= n; i++) {
                        for (int w = 0; w <= W; w++) {
                            dp[i][w] = dp[i - 1][w];
                            if (wt[i - 1] <= w) {
                                dp[i][w] = Math.max(dp[i][w], dp[i - 1][w - wt[i - 1]] + val[i - 1]);
                            }
                        }
                    }
                    return dp[n][W];
                }
                """);
        assertThat(found).hasSize(1);
        TableSpec t = found.get(0);
        assertThat(t.kind()).isEqualTo("tabulation");
        assertThat(t.problem()).isEqualTo("knapsack");
        assertThat(t.dims()).isEqualTo(2);
        assertThat(t.rowVar()).isEqualTo("i");
        assertThat(t.colVar()).isEqualTo("w");
        assertThat(t.loopDepth()).isEqualTo(2);
        TableSite take = t.sites().stream().filter(s -> s.line() == 10).findFirst().orElseThrow();
        assertThat(take.reads()).extracting(Expr::text).containsExactly("dp[i][w]", "dp[i - 1][w - wt[i - 1]]");
        assertThat(take.values()).extracting(Expr::text).containsExactly("val[i - 1]");
    }

    @Test
    void problemsAreNamedByTheShapeOfTheRecurrence() {
        assertThat(tables("""
                static int fib(int n) {
                    int[] dp = new int[n + 1];
                    dp[1] = 1;
                    for (int i = 2; i <= n; i++) dp[i] = dp[i - 1] + dp[i - 2];
                    return dp[n];
                }
                static int lcs(String a, String b) {
                    int[][] dp = new int[a.length() + 1][b.length() + 1];
                    for (int i = 1; i <= a.length(); i++)
                        for (int j = 1; j <= b.length(); j++)
                            dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1) ? dp[i - 1][j - 1] + 1
                                    : Math.max(dp[i - 1][j], dp[i][j - 1]);
                    return dp[a.length()][b.length()];
                }
                static int coins(int[] c, int amount) {
                    int[] dp = new int[amount + 1];
                    for (int a = 1; a <= amount; a++) {
                        dp[a] = 1000;
                        for (int k = 0; k < c.length; k++)
                            if (c[k] <= a) dp[a] = Math.min(dp[a], dp[a - c[k]] + 1);
                    }
                    return dp[amount];
                }
                static int chain(int[] d) {
                    int n = d.length - 1;
                    int[][] dp = new int[n + 1][n + 1];
                    for (int len = 2; len <= n; len++)
                        for (int i = 1; i <= n - len + 1; i++) {
                            int j = i + len - 1;
                            dp[i][j] = Integer.MAX_VALUE;
                            for (int k = i; k < j; k++)
                                dp[i][j] = Math.min(dp[i][j], dp[i][k] + dp[k + 1][j] + d[i - 1] * d[k] * d[j]);
                        }
                    return dp[1][n];
                }
                """)).extracting(TableSpec::problem).containsExactly("fibonacci", "lcs", "coin-change", "matrix-chain");
    }

    @Test
    void lcsRowsAndColumnsAreLabelledWithTheLetters() {
        TableSpec t = tables("""
                static int lcs(String a, String b) {
                    int[][] dp = new int[a.length() + 1][b.length() + 1];
                    for (int i = 1; i <= a.length(); i++)
                        for (int j = 1; j <= b.length(); j++)
                            if (a.charAt(i - 1) == b.charAt(j - 1)) dp[i][j] = dp[i - 1][j - 1] + 1;
                            else dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                    return dp[a.length()][b.length()];
                }
                """).get(0);
        assertThat(t.rowString()).isEqualTo("a");
        assertThat(t.rowOffset()).isEqualTo(1);
        assertThat(t.colString()).isEqualTo("b");
    }

    @Test
    void memoisationAndPlainRecursion() {
        CompilationUnit cu = parse("""
                static int fib(int n) { if (n <= 1) return n; return fib(n - 1) + fib(n - 2); }
                static int memo(int n, int[] m) {
                    if (n <= 1) return n;
                    if (m[n] != -1) return m[n];
                    m[n] = memo(n - 1, m) + memo(n - 2, m);
                    return m[n];
                }
                static Map<Integer, Long> cache = new HashMap<>();
                static long ways(int n) {
                    if (n <= 1) return 1;
                    if (cache.containsKey(n)) return cache.get(n);
                    long r = ways(n - 1) + ways(n - 2);
                    cache.put(n, r);
                    return r;
                }
                """);
        var recursions = DpModelBuilder.recursions(cu);
        assertThat(recursions).extracting(r -> r.method()).containsExactly("fib", "memo", "ways");
        assertThat(recursions.get(0).memo()).isNull();
        assertThat(recursions.get(1).memo()).isEqualTo("m");
        assertThat(recursions.get(1).checkStart()).isEqualTo(6);
        assertThat(recursions.get(2).memo()).isEqualTo("cache");
        // Only the array memo gets a table view.
        assertThat(DpModelBuilder.tables(cu)).extracting(TableSpec::table).containsExactly("m");
    }

    @Test
    void arraysThatAreNotDpTables() {
        CompilationUnit cu = parse("""
                static void insertionSort(int[] a) {
                    for (int i = 1; i < a.length; i++) {
                        int key = a[i]; int j = i - 1;
                        while (j >= 0 && a[j] > key) { a[j + 1] = a[j]; j--; }
                        a[j + 1] = key;
                    }
                }
                static int[] prefix(int[] a) {
                    int[] p = new int[a.length];
                    p[0] = a[0];
                    for (int i = 1; i < a.length; i++) p[i] = p[i - 1] + a[i];
                    return p;
                }
                static int[] bfs(int[][] g, int s) {
                    int[] dist = new int[g.length];
                    Arrays.fill(dist, -1); dist[s] = 0;
                    Queue<Integer> q = new LinkedList<>(); q.add(s);
                    while (!q.isEmpty()) {
                        int u = q.remove();
                        for (int v = 0; v < g.length; v++)
                            if (g[u][v] == 1 && dist[v] == -1) { dist[v] = dist[u] + 1; q.add(v); }
                    }
                    return dist;
                }
                static void mergeSort(int[] a, int lo, int hi) {
                    if (lo >= hi) return;
                    int mid = (lo + hi) / 2;
                    mergeSort(a, lo, mid);
                    mergeSort(a, mid + 1, hi);
                }
                static void sub(int[] a, int i, int sum) {
                    if (i == a.length) return;
                    sub(a, i + 1, sum + a[i]);
                    sub(a, i + 1, sum);
                }
                """);
        assertThat(DpModelBuilder.tables(cu)).isEmpty();
        assertThat(DpModelBuilder.recursions(cu)).isEmpty();
    }
}
