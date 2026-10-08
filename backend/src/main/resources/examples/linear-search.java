public class Main {
    static int linearSearch(int[] arr, int target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) {
                return i;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        int[] arr = {14, 3, 27, 8, 19, 5};
        int index = linearSearch(arr, 19);
        System.out.println("Found 19 at index " + index);
    }
}
