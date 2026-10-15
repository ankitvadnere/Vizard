package com.vizard.execution;

import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.ExecutionStatus;
import com.vizard.api.dto.trace.TraceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 10 hardening: the ways around the teaching-subset policy that a curious student might
 * try, and the resource limits that stop what the policy cannot see. Each program here must be
 * rejected before it runs, or stopped by the sandbox with a clear status.
 */
@SpringBootTest(properties = {
        "vizard.execution.timeout-ms=3000",
        "vizard.execution.max-output-bytes=10000"
})
class SecurityTest {

    @Autowired
    private ExecutionService service;

    private ExecutionResponse run(String body) {
        return service.execute("public class Main {\n public static void main(String[] args) throws Exception {\n"
                + body + "\n }\n}\n", "");
    }

    private void rejected(String body, String feature) {
        ExecutionResponse r = run(body);
        assertThat(r.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains(feature));
    }

    @Test
    void processesAreRejected() {
        rejected("Runtime.getRuntime().exec(\"ls\");", "Process control");
        rejected("new ProcessBuilder(\"ls\").start();", "Process control");
        rejected("ProcessHandle.current().pid();", "Process control");
    }

    @Test
    void threadsAreRejected() {
        rejected("new Thread(() -> {}).start();", "Threads");
        rejected("Thread.sleep(10);", "Threads");
        ExecutionResponse r = service.execute("""
                import java.util.List;
                public class Main {
                    public static void main(String[] args) { List.of(1, 2, 3).parallelStream().forEach(x -> { }); }
                }""", "");
        assertThat(r.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
        assertThat(r.problems()).anySatisfy(p -> assertThat(p.message()).contains("Threads"));
    }

    @Test
    void reflectionIsRejected() {
        rejected("Class.forName(\"java.lang.Runtime\");", "Reflection");
        rejected("String.class.getDeclaredMethods();", "Reflection");
        rejected("Object o = Main.class.getConstructor().newInstance();", "Reflection");
    }

    @Test
    void environmentAndPropertiesAreRejected() {
        rejected("System.out.println(System.getenv(\"PATH\"));", "System access");
        rejected("System.out.println(System.getProperty(\"user.home\"));", "System access");
        rejected("System.setOut(null);", "System access");
    }

    @Test
    void unicodeEscapesCannotHideForbiddenNames() {
        // \u0052untime is "Runtime" to javac
        ExecutionResponse r = run("\\u0052untime.getRuntime().exec(\"ls\");");
        assertThat(r.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
    }

    @Test
    void forbiddenPackagesCannotBeImported() {
        ExecutionResponse r = service.execute("""
                import java.util.concurrent.Executors;
                public class Main {
                    public static void main(String[] args) { Executors.newFixedThreadPool(2); }
                }""", "");
        assertThat(r.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
        ExecutionResponse star = service.execute("""
                import java.lang.reflect.*;
                public class Main {
                    public static void main(String[] args) { }
                }""", "");
        assertThat(star.status()).isEqualTo(ExecutionStatus.UNSUPPORTED_FEATURE);
    }

    @Test
    void deepRecursionIsAReadableRuntimeError() {
        ExecutionResponse r = service.execute("""
                public class Main {
                    static int down(int n) { return down(n + 1) + 1; }
                    public static void main(String[] args) { down(0); }
                }""", "");
        assertThat(r.status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
        assertThat(r.runtimeError().exceptionType()).contains("StackOverflowError");
    }

    @Test
    void anInfiniteTraceStopsAtTheStepLimit() {
        TraceResponse t = service.trace("""
                public class Main {
                    public static void main(String[] args) {
                        long i = 0;
                        while (true) { i++; }
                    }
                }""", "");
        assertThat(t.truncated() || t.execution().status() == ExecutionStatus.TIMEOUT).isTrue();
        assertThat(t.steps().size()).isLessThanOrEqualTo(t.maxSteps() + 1);
    }

    @Test
    void anInfiniteLoopInAGrowthRunIsStopped() {
        var g = service.growth("""
                public class Main {
                    public static void main(String[] args) {
                        int[] a = {3, 1, 2};
                        int i = 0;
                        while (a.length > 0) { i++; }
                    }
                }""", "");
        assertThat(g.points()).isEmpty();
        assertThat(g.reason()).isNotNull();
    }
}
