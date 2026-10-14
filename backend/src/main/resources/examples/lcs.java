public class Main {
    // Longest common subsequence: dp[i][j] = LCS length of a[0..i) and b[0..j).
    static int lcs(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;                  // same letter: extend
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);  // drop one letter
                }
            }
        }
        return dp[m][n];
    }

    public static void main(String[] args) {
        System.out.println("LCS length: " + lcs("ABCB", "BDCAB"));
    }
}
