public class Main {
    // Fewest coins that add up to amount: dp[a] = fewest coins for amount a.
    static int minCoins(int[] coins, int amount) {
        int[] dp = new int[amount + 1];
        for (int a = 1; a <= amount; a++) {
            dp[a] = Integer.MAX_VALUE;
            for (int c = 0; c < coins.length; c++) {
                if (coins[c] <= a && dp[a - coins[c]] != Integer.MAX_VALUE) {
                    dp[a] = Math.min(dp[a], dp[a - coins[c]] + 1);
                }
            }
        }
        return dp[amount];
    }

    public static void main(String[] args) {
        int[] coins = {1, 3, 4};
        System.out.println("Fewest coins for 6: " + minCoins(coins, 6));
    }
}
