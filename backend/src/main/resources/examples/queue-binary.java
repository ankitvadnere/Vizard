import java.util.LinkedList;
import java.util.Queue;

public class Main {
    public static void main(String[] args) {
        int n = 5;
        Queue<String> queue = new LinkedList<>();
        queue.offer("1");
        for (int i = 1; i <= n; i++) {
            String front = queue.poll();
            System.out.println(front);
            queue.offer(front + "0");
            queue.offer(front + "1");
        }
    }
}
