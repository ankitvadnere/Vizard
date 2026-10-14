package com.vizard.execution.insight;

import com.github.javaparser.StaticJavaParser;
import com.vizard.execution.insight.CodeModel.GraphSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding neighbour loops and the traversal state around them, by structure; and what is not a graph. */
class GraphModelBuilderTest {

    private static List<GraphSpec> specs(String methods) {
        return GraphModelBuilder.build(StaticJavaParser.parse("import java.util.*;\nclass Main {\n" + methods + "\n}"));
    }

    @Test
    void bfsOverAnAdjacencyList() {
        List<GraphSpec> found = specs("""
                static void bfs(List<List<Integer>> adj, int s) {
                    boolean[] seen = new boolean[adj.size()];
                    Queue<Integer> q = new ArrayDeque<>();
                    seen[s] = true;
                    q.offer(s);
                    while (!q.isEmpty()) {
                        int u = q.poll();
                        for (int v : adj.get(u)) {
                            if (!seen[v]) {
                                seen[v] = true;
                                q.offer(v);
                            }
                        }
                    }
                }
                """);
        assertThat(found).hasSize(1);
        GraphSpec s = found.get(0);
        assertThat(s.graph().text()).isEqualTo("adj");
        assertThat(s.shape()).isEqualTo("list");
        assertThat(s.vertex()).isEqualTo("u");
        assertThat(s.neighbor()).isEqualTo("v");
        assertThat(s.visited()).isEqualTo("seen");
        assertThat(s.frontier()).isEqualTo("q");
        assertThat(s.frontierOrder()).isEqualTo("queue");
        assertThat(s.frontierVertex()).isEqualTo("");
        assertThat(s.loopLine()).isEqualTo(10);
        assertThat(s.bodyStart()).isEqualTo(11);
    }

    @Test
    void weightedEdgesHeapAndRelaxation() {
        GraphSpec s = specs("""
                static int[] dijkstra(List<List<int[]>> g, int src) {
                    int[] d = new int[g.size()];
                    Arrays.fill(d, Integer.MAX_VALUE);
                    d[src] = 0;
                    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
                    pq.add(new int[]{0, src});
                    while (!pq.isEmpty()) {
                        int[] top = pq.poll();
                        int u = top[1];
                        for (int[] e : g.get(u)) {
                            int nd = d[u] + e[1];
                            if (nd < d[e[0]]) {
                                d[e[0]] = nd;
                                pq.add(new int[]{nd, e[0]});
                            }
                        }
                    }
                    return d;
                }
                """).get(0);
        assertThat(s.target()).isEqualTo("[0]");
        assertThat(s.weight()).isEqualTo("[1]");
        assertThat(s.dist()).isEqualTo("d");
        assertThat(s.relaxes()).isTrue();
        assertThat(s.frontierOrder()).isEqualTo("priority");
        assertThat(s.frontierVertex()).isEqualTo("[1]");
    }

    @Test
    void matrixWithMinimumScanInAHelper() {
        GraphSpec s = specs("""
                static int closest(int[] dist, boolean[] done) {
                    int best = -1, min = Integer.MAX_VALUE;
                    for (int v = 0; v < dist.length; v++) {
                        if (!done[v] && dist[v] < min) { min = dist[v]; best = v; }
                    }
                    return best;
                }
                static void dijkstra(int[][] w, int src) {
                    int n = w.length;
                    int[] dist = new int[n];
                    boolean[] done = new boolean[n];
                    for (int k = 0; k < n - 1; k++) {
                        int u = closest(dist, done);
                        done[u] = true;
                        for (int v = 0; v < n; v++) {
                            if (!done[v] && w[u][v] != 0 && dist[u] + w[u][v] < dist[v]) {
                                dist[v] = dist[u] + w[u][v];
                            }
                        }
                    }
                }
                """).get(0);
        assertThat(s.shape()).isEqualTo("matrix");
        assertThat(s.graph().text()).isEqualTo("w");
        assertThat(s.visited()).isEqualTo("done");
        assertThat(s.relaxes()).isTrue();
        assertThat(s.minScan()).isTrue();
        assertThat(s.frontier()).isNull();
    }

    @Test
    void recursiveDfsWithEdgeObjectsAndParentArray() {
        GraphSpec s = specs("""
                static class Edge { int to, weight; }
                static int[] parent;
                static void dfs(List<Edge>[] g, int u, boolean[] vis) {
                    vis[u] = true;
                    for (Edge e : g[u]) {
                        if (!vis[e.to]) {
                            parent[e.to] = u;
                            dfs(g, e.to, vis);
                        }
                    }
                }
                """).get(0);
        assertThat(s.target()).isEqualTo(".to");
        assertThat(s.recursive()).isTrue();
        assertThat(s.parent()).isEqualTo("parent");
        assertThat(s.visited()).isEqualTo("vis");
    }

    @Test
    void stackFrontierAndBfsLevels() {
        List<GraphSpec> found = specs("""
                static void dfs(List<List<Integer>> adj) {
                    boolean[] seen = new boolean[adj.size()];
                    Deque<Integer> st = new ArrayDeque<>();
                    st.push(0);
                    while (!st.isEmpty()) {
                        int u = st.pop();
                        if (seen[u]) continue;
                        seen[u] = true;
                        for (int v : adj.get(u)) if (!seen[v]) st.push(v);
                    }
                }
                static int[] levels(int[][] g) {
                    int[] level = new int[g.length];
                    Arrays.fill(level, -1);
                    level[0] = 0;
                    Queue<Integer> q = new LinkedList<>();
                    q.add(0);
                    while (!q.isEmpty()) {
                        int u = q.remove();
                        for (int v = 0; v < g.length; v++) {
                            if (g[u][v] == 1 && level[v] == -1) {
                                level[v] = level[u] + 1;
                                q.add(v);
                            }
                        }
                    }
                    return level;
                }
                """);
        assertThat(found).hasSize(2);
        assertThat(found.get(0).frontierOrder()).isEqualTo("stack");
        assertThat(found.get(1).shape()).isEqualTo("matrix");
        assertThat(found.get(1).levels()).isTrue();
        assertThat(found.get(1).relaxes()).isFalse();
    }

    @Test
    void textbookIteratorLoopOverAnArrayOfLists() {
        GraphSpec s = specs("""
                private LinkedList<Integer> adj[];
                void BFS(int s) {
                    boolean visited[] = new boolean[adj.length];
                    LinkedList<Integer> queue = new LinkedList<Integer>();
                    visited[s] = true;
                    queue.add(s);
                    while (queue.size() != 0) {
                        s = queue.poll();
                        Iterator<Integer> i = adj[s].listIterator();
                        while (i.hasNext()) {
                            int n = i.next();
                            if (!visited[n]) {
                                visited[n] = true;
                                queue.add(n);
                            }
                        }
                    }
                }
                """).get(0);
        assertThat(s.vertex()).isEqualTo("s");
        assertThat(s.neighbor()).isEqualTo("n");
        assertThat(s.visited()).isEqualTo("visited");
        assertThat(s.frontierOrder()).isEqualTo("queue");
    }

    @Test
    void tablesGridsAndBoardsAreNotGraphs() {
        assertThat(specs("""
                static int knapsack(int[] wt, int[] val, int W) {
                    int n = wt.length;
                    int[][] dp = new int[n + 1][W + 1];
                    for (int i = 1; i <= n; i++)
                        for (int w = 0; w <= W; w++) {
                            dp[i][w] = dp[i - 1][w];
                            if (wt[i - 1] <= w && dp[i - 1][w - wt[i - 1]] + val[i - 1] > dp[i][w])
                                dp[i][w] = dp[i - 1][w - wt[i - 1]] + val[i - 1];
                        }
                    return dp[n][W];
                }
                static void floyd(int[][] d, int n) {
                    for (int k = 0; k < n; k++)
                        for (int i = 0; i < n; i++)
                            for (int j = 0; j < n; j++)
                                if (d[i][k] + d[k][j] < d[i][j]) d[i][j] = d[i][k] + d[k][j];
                }
                static void multiply(int[][] a, int[][] b, int[][] c) {
                    for (int i = 0; i < 2; i++)
                        for (int j = 0; j < 2; j++)
                            for (int k = 0; k < 2; k++)
                                if (a[i][k] != 0) c[i][j] += a[i][k] * b[k][j];
                }
                static boolean solve(int[][] board, int row, int n) {
                    if (row == n) return true;
                    for (int col = 0; col < n; col++) {
                        if (isSafe(board, row, col)) {
                            board[row][col] = 1;
                            if (solve(board, row + 1, n)) return true;
                            board[row][col] = 0;
                        }
                    }
                    return false;
                }
                static boolean isSafe(int[][] board, int row, int col) {
                    for (int i = 0; i < row; i++) if (board[i][col] == 1) return false;
                    return true;
                }
                static void print(List<List<Integer>> adj) {
                    for (int u = 0; u < adj.size(); u++)
                        for (int v : adj.get(u)) System.out.print(u + "-" + v + " ");
                }
                """)).isEmpty();
    }
}
