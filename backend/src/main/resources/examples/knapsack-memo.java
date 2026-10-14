import java.util.Arrays;

public class Main {
    // 0/1 knapsack, top-down: best value from items i.. with capacity w, remembered in memo[i][w].
    static int best(int[] wt, int[] val, int i, int w, int[][] memo) {
        if (i == wt.length || w == 0) {
            return 0;
        }
        if (memo[i][w] != -1) {
            return memo[i][w];                              // solved before
        }
        int result = best(wt, val, i + 1, w, memo);         // leave item i
        if (wt[i] <= w) {
            result = Math.max(result, val[i] + best(wt, val, i + 1, w - wt[i], memo));  // take it
        }
        memo[i][w] = result;
        return result;
    }

    public static void main(String[] args) {
        int[] wt = {1, 2, 1, 2};
        int[] val = {10, 15, 20, 25};
        int W = 4;
        int[][] memo = new int[wt.length][W + 1];
        for (int[] row : memo) {
            Arrays.fill(row, -1);
        }
        System.out.println("Best value with capacity 4: " + best(wt, val, 0, W, memo));
    }
}
