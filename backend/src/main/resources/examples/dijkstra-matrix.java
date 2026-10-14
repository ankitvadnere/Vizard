import java.util.Arrays;

public class Main {
    // The unvisited vertex with the smallest known distance (a linear scan: O(V)).
    static int minDistance(int[] dist, boolean[] done) {
        int min = Integer.MAX_VALUE;
        int best = -1;
        for (int v = 0; v < dist.length; v++) {
            if (!done[v] && dist[v] < min) {
                min = dist[v];
                best = v;
            }
        }
        return best;
    }

    // Dijkstra's shortest paths on an adjacency matrix (0 = no edge), without a heap.
    static int[] dijkstra(int[][] graph, int src) {
        int n = graph.length;
        int[] dist = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[src] = 0;
        for (int count = 0; count < n - 1; count++) {
            int u = minDistance(dist, done);
            done[u] = true;
            for (int v = 0; v < n; v++) {
                if (!done[v] && graph[u][v] != 0 && dist[u] + graph[u][v] < dist[v]) {
                    dist[v] = dist[u] + graph[u][v];
                }
            }
        }
        return dist;
    }

    public static void main(String[] args) {
        int[][] graph = {
            {0, 4, 1, 0, 0},
            {4, 0, 2, 1, 0},
            {1, 2, 0, 5, 0},
            {0, 1, 5, 0, 3},
            {0, 0, 0, 3, 0}
        };
        int[] dist = dijkstra(graph, 0);
        System.out.println("Shortest distances from 0: " + Arrays.toString(dist));
    }
}
