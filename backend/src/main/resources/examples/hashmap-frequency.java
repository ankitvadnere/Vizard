import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        String[] words = {"to", "be", "or", "not", "to", "be"};
        Map<String, Integer> count = new HashMap<>();
        for (String word : words) {
            count.put(word, count.getOrDefault(word, 0) + 1);
        }
        System.out.println(count);
        System.out.println("\"be\" appears " + count.get("be") + " times");
    }
}
