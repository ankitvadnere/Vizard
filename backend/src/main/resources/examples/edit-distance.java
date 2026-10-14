public class Main {
    // Edit distance: dp[i][j] = fewest insert/delete/replace steps to turn a[0..i) into b[0..j).
    static int editDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;                                   // delete everything
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;                                   // insert everything
        }
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];            // letters match: no cost
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp[m][n];
    }

    public static void main(String[] args) {
        System.out.println("Edit distance: " + editDistance("SUN", "SAT"));
    }
}
