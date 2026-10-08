public class Main {
    public static void main(String[] args) {
        int[] marks = {72, 95, 61, 88};
        int sum = 0;
        int max = marks[0];
        for (int i = 0; i < marks.length; i++) {
            sum += marks[i];
            if (marks[i] > max) {
                max = marks[i];
            }
        }
        System.out.println("Sum: " + sum + ", max: " + max);
    }
}
