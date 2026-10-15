// Neither method is a textbook algorithm Vizard recognises, so the Complexity tab
// estimates them from the code: nested loops for one, a recurrence for the other.
public class Main {
    // Largest sum of a contiguous part of the array, trying every start and end.
    static int maxSubarray(int[] a) {
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < a.length; i++) {
            int sum = 0;
            for (int j = i; j < a.length; j++) {
                sum += a[j];
                best = Math.max(best, sum);
            }
        }
        return best;
    }

    // x to the power n by squaring: T(n) = T(n/2) + O(1).
    static long power(long x, int n) {
        if (n == 0) {
            return 1;
        }
        long half = power(x, n / 2);
        return n % 2 == 0 ? half * half : half * half * x;
    }

    public static void main(String[] args) {
        int[] a = {-2, 1, -3, 4, -1, 2, 1, -5, 4};
        System.out.println("Max subarray sum: " + maxSubarray(a));
        System.out.println("3^10 = " + power(3, 10));
    }
}
