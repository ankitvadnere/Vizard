public class Main {
    static final int N = 4;
    // cost[w][j] = cost of worker w doing job j
    static int[][] cost = {
        {9, 2, 7, 8},
        {6, 4, 3, 7},
        {5, 8, 1, 8},
        {7, 6, 9, 4}
    };
    static int best = Integer.MAX_VALUE;

    // Lower bound: cost so far + the cheapest free job for every worker still to assign.
    static int lowerBound(int worker, int costSoFar, boolean[] taken) {
        int bound = costSoFar;
        for (int w = worker; w < N; w++) {
            int min = Integer.MAX_VALUE;
            for (int j = 0; j < N; j++) {
                if (!taken[j] && cost[w][j] < min) {
                    min = cost[w][j];
                }
            }
            bound += min;
        }
        return bound;
    }

    // Give worker `worker` each free job in turn; prune when the bound can't beat the best.
    static void assign(int worker, int costSoFar, boolean[] taken) {
        if (worker == N) {
            if (costSoFar < best) {
                best = costSoFar;
            }
            return;
        }
        for (int job = 0; job < N; job++) {
            if (!taken[job]) {
                taken[job] = true;
                int c = costSoFar + cost[worker][job];
                if (lowerBound(worker + 1, c, taken) < best) {
                    assign(worker + 1, c, taken);
                }
                taken[job] = false;
            }
        }
    }

    public static void main(String[] args) {
        assign(0, 0, new boolean[N]);
        System.out.println("Cheapest assignment: " + best);
    }
}
