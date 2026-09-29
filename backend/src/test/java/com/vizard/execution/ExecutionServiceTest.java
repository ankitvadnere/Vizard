package com.vizard.execution;

import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the Milestone 1 pipeline: parse → check → compile → sandboxed run.
 * A short timeout keeps the infinite-loop test fast.
 */
@SpringBootTest(properties = {
        "vizard.execution.timeout-ms=3000",
        "vizard.execution.max-output-bytes=10000"
})
class ExecutionServiceTest {

    @Autowired
    private ExecutionService service;

    private ExecutionResponse run(String code) {
        return service.execute(code, "");
    }

    @Test
    void helloWorldPrintsOutput() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello, Vizard!");
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.stdout().strip()).isEqualTo("Hello, Vizard!");
    }

    @Test
    void loopsAndArraysWork() {
        ExecutionResponse r = run("""
                import java.util.Arrays;
                public class Main {
                    public static void main(String[] args) {
                        int[] arr = {5, 2, 8, 1};
                        Arrays.sort(arr);
                        int sum = 0;
                        for (int x : arr) sum += x;
                        System.out.println(Arrays.toString(arr) + " " + sum);
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.stdout().strip()).isEqualTo("[1, 2, 5, 8] 16");
    }

    @Test
    void syntaxErrorReportsLine() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 5
                        System.out.println(x);
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.COMPILATION_ERROR);
        assertThat(r.problems()).anySatisfy(p -> {
            assertThat(p.line()).isEqualTo(3);
            assertThat(p.message()).contains("';' expected");
        });
    }

    @Test
    void typeErrorReportsLine() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        int x = "hello";
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.COMPILATION_ERROR);
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.line()).isEqualTo(3));
    }

    @Test
    void runtimeExceptionMapsToSourceLine() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        int[] arr = {1, 2, 3};
                        System.out.println("before");
                        System.out.println(arr[5]);
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
        assertThat(r.stdout()).contains("before");
        assertThat(r.runtimeError().exceptionType()).isEqualTo("java.lang.ArrayIndexOutOfBoundsException");
        assertThat(r.runtimeError().line()).isEqualTo(5);
    }

    @Test
    void infiniteLoopTimesOut() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        while (true) { }
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.TIMEOUT);
    }

    @Test
    void hugeOutputIsCut() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        while (true) System.out.println("spam spam spam");
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.OUTPUT_LIMIT_EXCEEDED);
        assertThat(r.stdout().length()).isLessThanOrEqualTo(10000);
    }

    @Test
    void hugeAllocationHitsMemoryLimit() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        long[] big = new long[200_000_000];
                        System.out.println(big.length);
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.MEMORY_LIMIT_EXCEEDED);
    }

    @Test
    void fileAccessIsRejectedBeforeRunning() {
        ExecutionResponse r = run("""
                import java.io.File;
                public class Main {
                    public static void main(String[] args) {
                        File f = new File("secret.txt");
                        System.out.println(f.exists());
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("File I/O"));
    }

    @Test
    void networkAndFullyQualifiedNamesAreRejected() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) throws Exception {
                        var s = new java.net.Socket("example.com", 80);
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
    }

    @Test
    void systemExitIsRejected() {
        ExecutionResponse r = run("""
                public class Main {
                    public static void main(String[] args) {
                        System.exit(0);
                    }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
    }

    @Test
    void stdinReachesScanner() {
        ExecutionResponse r = service.execute("""
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        int a = sc.nextInt(), b = sc.nextInt();
                        System.out.println(a + b);
                    }
                }
                """, "4 5");
        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.stdout().strip()).isEqualTo("9");
    }

    @Test
    void missingMainIsReported() {
        ExecutionResponse r = run("""
                public class Main {
                    static int add(int a, int b) { return a + b; }
                }
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.COMPILATION_ERROR);
        assertThat(r.message()).contains("No entry point");
    }
}
