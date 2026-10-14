public class Main {
    // Matrix chain multiplication: dims has n + 1 numbers for n matrices (A_i is dims[i-1] x dims[i]).
    // dp[i][j] = fewest scalar multiplications to compute A_i ... A_j.
    static int matrixChain(int[] dims) {
        int n = dims.length - 1;
        int[][] dp = new int[n + 1][n + 1];
        for (int len = 2; len <= n; len++) {
            for (int i = 1; i <= n - len + 1; i++) {
                int j = i + len - 1;
                dp[i][j] = Integer.MAX_VALUE;
                for (int k = i; k < j; k++) {               // split between A_k and A_k+1
                    int cost = dp[i][k] + dp[k + 1][j] + dims[i - 1] * dims[k] * dims[j];
                    if (cost < dp[i][j]) {
                        dp[i][j] = cost;
                    }
                }
            }
        }
        return dp[1][n];
    }

    public static void main(String[] args) {
        int[] dims = {10, 20, 5, 30, 10};
        System.out.println("Fewest multiplications: " + matrixChain(dims));
    }
}
