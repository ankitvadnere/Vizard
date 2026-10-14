import java.util.Arrays;

public class Main {
    static int naiveCalls = 0;
    static int memoCalls = 0;

    // 1. Plain recursion: solves the same subproblems again and again.
    static int fibNaive(int n) {
        naiveCalls++;
        if (n <= 1) {
            return n;
        }
        return fibNaive(n - 1) + fibNaive(n - 2);
    }

    // 2. Memoisation (top-down): remember each answer the first time.
    static int fibMemo(int n, int[] memo) {
        memoCalls++;
        if (n <= 1) {
            return n;
        }
        if (memo[n] != -1) {
            return memo[n];                      // already solved
        }
        memo[n] = fibMemo(n - 1, memo) + fibMemo(n - 2, memo);
        return memo[n];
    }

    // 3. Tabulation (bottom-up): fill the table from the smallest subproblem up.
    static int fibTable(int n) {
        int[] dp = new int[n + 1];
        dp[0] = 0;
        dp[1] = 1;
        for (int i = 2; i <= n; i++) {
            dp[i] = dp[i - 1] + dp[i - 2];
        }
        return dp[n];
    }

    public static void main(String[] args) {
        int n = 6;
        System.out.println("naive:  " + fibNaive(n) + " (" + naiveCalls + " calls)");
        int[] memo = new int[n + 1];
        Arrays.fill(memo, -1);
        System.out.println("memo:   " + fibMemo(n, memo) + " (" + memoCalls + " calls)");
        System.out.println("table:  " + fibTable(n) + " (" + (n - 1) + " cells computed)");
    }
}
