package com.vizard.execution.algorithms;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** BFS, DFS and Dijkstra recognisers, the representation that decides their complexity, and look-alikes. */
class GraphAlgorithmsTest {

    private static List<Detection> detect(String methods) {
        return AlgorithmDetector.detect(StaticJavaParser.parse("import java.util.*;\nclass Main {\n" + methods + "\n}"));
    }

    private static final String BFS_LIST = """
            static void bfs(List<List<Integer>> adj, int s) {
                boolean[] seen = new boolean[adj.size()];
                Queue<Integer> q = new ArrayDeque<>();
                seen[s] = true;
                q.offer(s);
                while (!q.isEmpty()) {
                    int u = q.poll();
                    for (int v : adj.get(u)) {
                        if (!seen[v]) { seen[v] = true; q.offer(v); }
                    }
                }
            }
            """;

    private static final String DIJKSTRA_MATRIX = """
            static int minDistance(int[] dist, boolean[] done) {
                int min = Integer.MAX_VALUE, best = -1;
                for (int v = 0; v < dist.length; v++)
                    if (!done[v] && dist[v] <= min) { min = dist[v]; best = v; }
                return best;
            }
            static void dijkstra(int[][] graph, int src) {
                int n = graph.length;
                int[] dist = new int[n];
                boolean[] done = new boolean[n];
                Arrays.fill(dist, Integer.MAX_VALUE);
                dist[src] = 0;
                for (int count = 0; count < n - 1; count++) {
                    int u = minDistance(dist, done);
                    done[u] = true;
                    for (int v = 0; v < n; v++)
                        if (!done[v] && graph[u][v] != 0 && dist[u] + graph[u][v] < dist[v])
                            dist[v] = dist[u] + graph[u][v];
                }
            }
            """;

    @Test
    void bfsOnAListIsLinearInVPlusE() {
        List<Detection> found = detect(BFS_LIST);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.BFS);
        var match = AlgorithmCatalog.describe(found.get(0), null, StructureFacts.NONE.withGraph(6, 7, false));
        assertThat(match.complexity().worst()).isEqualTo("O(V + E)");
        assertThat(match.bound().formula()).isEqualTo("2E");
        assertThat(match.bound().value()).isEqualTo(14L);
        assertThat(match.measures()).extracting(m -> m.value()).contains("6", "7", "13");
    }

    @Test
    void bfsOnAMatrixIsQuadratic() {
        List<Detection> found = detect("""
                static void bfs(int[][] g, int s) {
                    int[] level = new int[g.length];
                    Arrays.fill(level, -1);
                    level[s] = 0;
                    Queue<Integer> q = new LinkedList<>();
                    q.add(s);
                    while (!q.isEmpty()) {
                        int u = q.remove();
                        for (int v = 0; v < g.length; v++)
                            if (g[u][v] == 1 && level[v] == -1) { level[v] = level[u] + 1; q.add(v); }
                    }
                }
                """);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.BFS);
        assertThat(found.get(0).traits()).contains(GraphAlgorithms.MATRIX);
        var match = AlgorithmCatalog.describe(found.get(0), null, StructureFacts.NONE.withGraph(4, 4, false));
        assertThat(match.complexity().worst()).isEqualTo("O(V²)");
        assertThat(match.bound().value()).isEqualTo(16L);
    }

    @Test
    void dfsRecursiveAndIterative() {
        List<Detection> found = detect("""
                static void dfs(List<List<Integer>> adj, int u, boolean[] visited) {
                    visited[u] = true;
                    for (int v : adj.get(u)) if (!visited[v]) dfs(adj, v, visited);
                }
                static void dfsStack(List<List<Integer>> adj, int s) {
                    boolean[] visited = new boolean[adj.size()];
                    Deque<Integer> stack = new ArrayDeque<>();
                    stack.push(s);
                    while (!stack.isEmpty()) {
                        int u = stack.pop();
                        if (visited[u]) continue;
                        visited[u] = true;
                        for (int v : adj.get(u)) if (!visited[v]) stack.push(v);
                    }
                }
                """);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.DFS, Algorithm.DFS);
        assertThat(found.get(0).traits()).contains(Detection.RECURSIVE);
        assertThat(found.get(1).traits()).contains(GraphAlgorithms.ITERATIVE);
        assertThat(AlgorithmCatalog.describe(found.get(1), null).complexity().space()).isEqualTo("O(V + E)");
    }

    @Test
    void dijkstraWithAHeap() {
        List<Detection> found = detect("""
                static int[] dijkstra(List<List<int[]>> adj, int src) {
                    int[] dist = new int[adj.size()];
                    Arrays.fill(dist, Integer.MAX_VALUE);
                    dist[src] = 0;
                    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
                    pq.offer(new int[]{0, src});
                    while (!pq.isEmpty()) {
                        int[] top = pq.poll();
                        int u = top[1];
                        if (top[0] > dist[u]) continue;
                        for (int[] e : adj.get(u)) {
                            int v = e[0], w = e[1];
                            if (dist[u] + w < dist[v]) {
                                dist[v] = dist[u] + w;
                                pq.offer(new int[]{dist[v], v});
                            }
                        }
                    }
                    return dist;
                }
                """);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.DIJKSTRA);
        assertThat(found.get(0).traits()).contains(GraphAlgorithms.HEAP, GraphAlgorithms.SKIPS_STALE);
        var match = AlgorithmCatalog.describe(found.get(0), null, StructureFacts.NONE.withGraph(5, 6, false));
        assertThat(match.complexity().worst()).isEqualTo("O((V+E) log V)");
        assertThat(match.bound().value()).isEqualTo(12L);
    }

    @Test
    void dijkstraWithoutAHeapAndItsHelperIsNotReportedAlone() {
        List<Detection> found = detect(DIJKSTRA_MATRIX);
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.DIJKSTRA);
        assertThat(found.get(0).method()).isEqualTo("dijkstra");
        assertThat(found.get(0).traits()).doesNotContain(GraphAlgorithms.HEAP);
        var match = AlgorithmCatalog.describe(found.get(0), null, StructureFacts.NONE.withGraph(5, 6, false));
        assertThat(match.complexity().worst()).isEqualTo("O(V²)");
        assertThat(match.bound().value()).isEqualTo(25L);
    }

    @Test
    void lookAlikesAreNotClaimed() {
        List<Detection> found = detect("""
                static int prim(List<List<int[]>> adj) {
                    int n = adj.size(); boolean[] inMst = new boolean[n]; int[] key = new int[n];
                    Arrays.fill(key, Integer.MAX_VALUE); key[0] = 0; int total = 0;
                    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
                    pq.offer(new int[]{0, 0});
                    while (!pq.isEmpty()) {
                        int[] top = pq.poll(); int u = top[1];
                        if (inMst[u]) continue;
                        inMst[u] = true; total += top[0];
                        for (int[] e : adj.get(u)) {
                            int v = e[0], w = e[1];
                            if (!inMst[v] && w < key[v]) { key[v] = w; pq.offer(new int[]{w, v}); }
                        }
                    }
                    return total;
                }
                static void kahn(List<List<Integer>> adj, int[] indeg) {
                    Queue<Integer> q = new ArrayDeque<>();
                    for (int i = 0; i < adj.size(); i++) if (indeg[i] == 0) q.add(i);
                    while (!q.isEmpty()) {
                        int u = q.poll();
                        for (int v : adj.get(u)) {
                            indeg[v]--;
                            if (indeg[v] == 0) q.add(v);
                        }
                    }
                }
                static void spfa(List<List<int[]>> adj, int[] dist) {
                    Queue<Integer> q = new ArrayDeque<>(); q.add(0);
                    while (!q.isEmpty()) {
                        int u = q.poll();
                        for (int[] e : adj.get(u))
                            if (dist[u] + e[1] < dist[e[0]]) { dist[e[0]] = dist[u] + e[1]; q.add(e[0]); }
                    }
                }
                static void floyd(int[][] d, int n) {
                    for (int k = 0; k < n; k++)
                        for (int i = 0; i < n; i++)
                            for (int j = 0; j < n; j++)
                                if (d[i][k] + d[k][j] < d[i][j]) d[i][j] = d[i][k] + d[k][j];
                }
                """);
        assertThat(found).extracting(Detection::algorithm).doesNotContainAnyElementsOf(
                Set.of(Algorithm.BFS, Algorithm.DFS, Algorithm.DIJKSTRA));
    }
}
