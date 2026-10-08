// Example programs. In a later milestone these move to GET /api/examples.

export const EXAMPLES = [
    {
        id: "hello",
        title: "Hello, Vizard",
        code: `public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, Vizard!");
    }
}
`,
    },
    {
        id: "variables",
        title: "Basic variables",
        code: `public class Main {
    public static void main(String[] args) {
        int x = 10;
        double price = 20.5;
        boolean flag = true;
        String name = "Ankit";

        int total = x * 3;
        System.out.println(name + " has " + total + " points");
        System.out.println("Price with tax: " + (price * 1.18));
        System.out.println("Flag is " + !flag);
    }
}
`,
    },
    {
        id: "loops",
        title: "Loops",
        code: `public class Main {
    public static void main(String[] args) {
        int sum = 0;
        for (int i = 1; i <= 5; i++) {
            sum += i;
            System.out.println("i = " + i + ", sum = " + sum);
        }

        int n = 10;
        while (n > 0) {
            n = n / 2;
        }
        System.out.println("n ended at " + n);
    }
}
`,
    },
    {
        id: "arrays",
        title: "Arrays (sum and max)",
        code: `public class Main {
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
`,
    },
    {
        id: "bubble",
        title: "Bubble sort",
        code: `public class Main {
    public static void main(String[] args) {
        int[] arr = {5, 2, 8, 1};

        for (int i = 0; i < arr.length; i++) {
            for (int j = 0; j < arr.length - i - 1; j++) {
                if (arr[j] > arr[j + 1]) {
                    int temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                }
            }
        }

        for (int value : arr) {
            System.out.print(value + " ");
        }
        System.out.println();
    }
}
`,
    },
    {
        id: "selection",
        title: "Selection sort",
        code: `public class Main {
    public static void main(String[] args) {
        int[] arr = {29, 10, 14, 37, 13};
        for (int i = 0; i < arr.length - 1; i++) {
            int min = i;
            for (int j = i + 1; j < arr.length; j++) {
                if (arr[j] < arr[min]) {
                    min = j;
                }
            }
            int temp = arr[i];
            arr[i] = arr[min];
            arr[min] = temp;
        }
        for (int value : arr) {
            System.out.print(value + " ");
        }
    }
}
`,
    },
    {
        id: "insertion",
        title: "Insertion sort",
        code: `public class Main {
    public static void main(String[] args) {
        int[] arr = {9, 5, 1, 4, 3};
        for (int i = 1; i < arr.length; i++) {
            int key = arr[i];
            int j = i - 1;
            while (j >= 0 && arr[j] > key) {
                arr[j + 1] = arr[j];
                j--;
            }
            arr[j + 1] = key;
        }
        for (int value : arr) {
            System.out.print(value + " ");
        }
    }
}
`,
    },
    {
        id: "binary-search",
        title: "Binary search",
        code: `public class Main {
    static int binarySearch(int[] arr, int target) {
        int low = 0, high = arr.length - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            if (arr[mid] == target) return mid;
            if (arr[mid] < target) low = mid + 1;
            else high = mid - 1;
        }
        return -1;
    }

    public static void main(String[] args) {
        int[] arr = {1, 3, 5, 7, 9, 11, 13};
        System.out.println("Index of 9: " + binarySearch(arr, 9));
        System.out.println("Index of 4: " + binarySearch(arr, 4));
    }
}
`,
    },
    {
        id: "recursion",
        title: "Recursion (factorial)",
        code: `public class Main {
    static int factorial(int n) {
        if (n == 0)
            return 1;
        return n * factorial(n - 1);
    }

    public static void main(String[] args) {
        System.out.println("4! = " + factorial(4));
    }
}
`,
    },
    {
        id: "input",
        title: "Reading input",
        code: `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int a = sc.nextInt();
        int b = sc.nextInt();
        System.out.println(a + " + " + b + " = " + (a + b));
    }
}
`,
        stdin: "4 5",
    },
    {
        id: "runtime-error",
        title: "Runtime error demo",
        code: `public class Main {
    public static void main(String[] args) {
        int[] arr = {1, 2, 3};
        for (int i = 0; i <= arr.length; i++) {
            System.out.println(arr[i]);
        }
    }
}
`,
    },
];
