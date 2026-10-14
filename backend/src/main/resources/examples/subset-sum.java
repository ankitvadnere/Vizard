import java.util.ArrayList;
import java.util.List;

public class Main {
    static int solutions = 0;

    // For each element: take it, or leave it. Stop early once the sum is too big.
    static void subsetSum(int[] a, int i, int sum, int target, List<Integer> chosen) {
        if (sum > target) {
            return;                                // prune: adding more can only make it bigger
        }
        if (i == a.length) {
            if (sum == target) {
                System.out.println(chosen);
                solutions++;
            }
            return;
        }
        chosen.add(a[i]);                          // take a[i]
        subsetSum(a, i + 1, sum + a[i], target, chosen);
        chosen.remove(chosen.size() - 1);          // undo
        subsetSum(a, i + 1, sum, target, chosen);  // leave a[i]
    }

    public static void main(String[] args) {
        int[] a = {3, 1, 4, 2};
        subsetSum(a, 0, 0, 5, new ArrayList<>());
        System.out.println(solutions + " subsets add up to 5");
    }
}
