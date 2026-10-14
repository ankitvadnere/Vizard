import java.util.Arrays;

public class Main {
    static final int V = 4;

    // Can vertex v get colour c? Not if a neighbour already has it.
    static boolean isSafe(int v, int[][] graph, int[] color, int c) {
        for (int i = 0; i < V; i++) {
            if (graph[v][i] == 1 && color[i] == c) {
                return false;
            }
        }
        return true;
    }

    // Colour vertices 0, 1, 2, ... in order with colours 1..m; backtrack on a conflict.
    static boolean colour(int[][] graph, int m, int[] color, int v) {
        if (v == V) {
            return true;
        }
        for (int c = 1; c <= m; c++) {
            if (isSafe(v, graph, color, c)) {
                color[v] = c;                       // choose
                if (colour(graph, m, color, v + 1)) {
                    return true;
                }
                color[v] = 0;                       // undo
            }
        }
        return false;
    }

    public static void main(String[] args) {
        int[][] graph = {
            {0, 1, 1, 1},
            {1, 0, 1, 0},
            {1, 1, 0, 1},
            {1, 0, 1, 0}
        };
        int[] color = new int[V];
        if (colour(graph, 3, color, 0)) {
            System.out.println("Colours: " + Arrays.toString(color));
        } else {
            System.out.println("Not possible with 3 colours");
        }
    }
}
