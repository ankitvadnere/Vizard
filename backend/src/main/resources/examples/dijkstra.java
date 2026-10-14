import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

public class Main {
    // Dijkstra's shortest paths with a min-heap. Each edge is int[]{to, weight};
    // each heap entry is int[]{distance, vertex}.
    static int[] dijkstra(List<List<int[]>> adj, int src) {
        int[] dist = new int[adj.size()];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[src] = 0;
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[0] - b[0]);
        pq.offer(new int[]{0, src});
        while (!pq.isEmpty()) {
            int[] top = pq.poll();
            int u = top[1];
            if (top[0] > dist[u]) {
                continue; // an outdated entry: u was already reached by a shorter path
            }
            for (int[] e : adj.get(u)) {
                int v = e[0];
                int w = e[1];
                if (dist[u] + w < dist[v]) {
                    dist[v] = dist[u] + w;
                    pq.offer(new int[]{dist[v], v});
                }
            }
        }
        return dist;
    }

    static void addEdge(List<List<int[]>> adj, int u, int v, int w) {
        adj.get(u).add(new int[]{v, w});
        adj.get(v).add(new int[]{u, w});
    }

    public static void main(String[] args) {
        int n = 5;
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        addEdge(adj, 0, 1, 4);
        addEdge(adj, 0, 2, 1);
        addEdge(adj, 2, 1, 2);
        addEdge(adj, 1, 3, 1);
        addEdge(adj, 2, 3, 5);
        addEdge(adj, 3, 4, 3);
        int[] dist = dijkstra(adj, 0);
        System.out.println("Shortest distances from 0: " + Arrays.toString(dist));
    }
}
