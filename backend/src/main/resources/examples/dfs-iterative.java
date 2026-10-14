import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class Main {
    // Depth-first search with an explicit stack instead of recursion.
    static void dfs(List<List<Integer>> adj, int start) {
        boolean[] visited = new boolean[adj.size()];
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int u = stack.pop();
            if (visited[u]) {
                continue;
            }
            visited[u] = true;
            System.out.print(u + " ");
            for (int v : adj.get(u)) {
                if (!visited[v]) {
                    stack.push(v);
                }
            }
        }
        System.out.println();
    }

    static void addEdge(List<List<Integer>> adj, int u, int v) {
        adj.get(u).add(v);
        adj.get(v).add(u);
    }

    public static void main(String[] args) {
        int n = 6;
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        addEdge(adj, 0, 1);
        addEdge(adj, 0, 2);
        addEdge(adj, 1, 3);
        addEdge(adj, 2, 4);
        addEdge(adj, 3, 5);
        addEdge(adj, 4, 5);
        addEdge(adj, 1, 4);
        System.out.print("DFS from 0: ");
        dfs(adj, 0);
    }
}
