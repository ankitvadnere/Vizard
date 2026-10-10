import java.util.PriorityQueue;

public class Main {
    public static void main(String[] args) {
        int[] tasks = {5, 1, 8, 3, 2};
        PriorityQueue<Integer> heap = new PriorityQueue<>();
        for (int task : tasks) {
            heap.offer(task);
        }
        while (!heap.isEmpty()) {
            System.out.print(heap.poll() + " ");
        }
        System.out.println();
    }
}
