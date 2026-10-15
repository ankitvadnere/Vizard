public class Main {
    static final int N = 5;
    static int[][] dist = {
        {0, 3, 10, 7, 4},
        {3, 0, 6, 9, 8},
        {10, 6, 0, 5, 12},
        {7, 9, 5, 0, 6},
        {4, 8, 12, 6, 0}
    };
    static int best;

    // Plain backtracking: try every order of the remaining cities.
    static void tour(int city, int count, int cost, boolean[] visited) {
        if (count == N) {
            best = Math.min(best, cost + dist[city][0]);    // back to city 0
            return;
        }
        for (int next = 0; next < N; next++) {
            if (!visited[next]) {
                visited[next] = true;
                tour(next, count + 1, cost + dist[city][next], visited);
                visited[next] = false;
            }
        }
    }

    // Branch and bound: skip a city if the cost so far already reaches the best tour found.
    static void tourBnB(int city, int count, int cost, boolean[] visited) {
        if (count == N) {
            best = Math.min(best, cost + dist[city][0]);
            return;
        }
        for (int next = 0; next < N; next++) {
            if (!visited[next] && cost + dist[city][next] < best) {
                visited[next] = true;
                tourBnB(next, count + 1, cost + dist[city][next], visited);
                visited[next] = false;
            }
        }
    }

    public static void main(String[] args) {
        boolean[] visited = new boolean[N];
        visited[0] = true;
        best = Integer.MAX_VALUE;
        tour(0, 1, 0, visited);
        System.out.println("Backtracking: shortest tour = " + best);
        best = Integer.MAX_VALUE;
        tourBnB(0, 1, 0, visited);
        System.out.println("Branch and bound: shortest tour = " + best);
    }
}
