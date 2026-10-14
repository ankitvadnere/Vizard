import java.util.ArrayList;
import java.util.List;

public class Main {
    // Build every ordering of nums, one position at a time.
    static void permute(int[] nums, boolean[] used, List<Integer> path) {
        if (path.size() == nums.length) {
            System.out.println(path);
            return;
        }
        for (int i = 0; i < nums.length; i++) {
            if (!used[i]) {
                used[i] = true;                    // choose nums[i]
                path.add(nums[i]);
                permute(nums, used, path);         // explore
                path.remove(path.size() - 1);      // undo
                used[i] = false;
            }
        }
    }

    public static void main(String[] args) {
        int[] nums = {1, 2, 3};
        permute(nums, new boolean[nums.length], new ArrayList<>());
    }
}
