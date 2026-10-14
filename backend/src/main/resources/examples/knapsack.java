public class Main {
    // 0/1 knapsack, bottom-up: dp[i][w] = best value using the first i items with capacity w.
    static int knapsack(int[] wt, int[] val, int W) {
        int n = wt.length;
        int[][] dp = new int[n + 1][W + 1];
        for (int i = 1; i <= n; i++) {
            for (int w = 0; w <= W; w++) {
                dp[i][w] = dp[i - 1][w];                       // leave item i
                if (wt[i - 1] <= w) {
                    dp[i][w] = Math.max(dp[i][w], dp[i - 1][w - wt[i - 1]] + val[i - 1]);  // or take it
                }
            }
        }
        return dp[n][W];
    }

    public static void main(String[] args) {
        int[] wt = {1, 3, 4};
        int[] val = {15, 20, 30};
        System.out.println("Best value with capacity 5: " + knapsack(wt, val, 5));
    }
}
