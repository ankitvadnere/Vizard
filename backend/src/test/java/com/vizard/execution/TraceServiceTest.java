package com.vizard.execution;

import com.vizard.api.dto.ExecutionStatus;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 2: the debugger-based trace must reflect exactly what the JVM did.
 */
@SpringBootTest(properties = {
        "vizard.execution.timeout-ms=3000",
        "vizard.execution.trace-timeout-ms=10000",
        "vizard.execution.max-trace-steps=500"
})
class TraceServiceTest {

    @Autowired
    private ExecutionService service;

    // ---- helpers -----------------------------------------------------------------------

    private TraceResponse trace(String code) {
        return service.trace(code, "");
    }

    private static Optional<ValueSnapshot> local(TraceStep step, String name) {
        return step.stack().get(0).variables().stream()
                .filter(v -> v.name().equals(name))
                .map(VariableSnapshot::value)
                .findFirst();
    }

    private static String arrayText(TraceStep step, String name) {
        ValueSnapshot ref = local(step, name).orElseThrow();
        HeapObjectSnapshot array = step.heap().get(ref.ref().toString());
        return array.elements().stream().map(ValueSnapshot::display).collect(Collectors.joining(","));
    }

    // ---- tests -------------------------------------------------------------------------

    @Test
    void followsLinesAndCapturesVariables() {
        TraceResponse r = trace("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 5;
                        int y = 10;
                        int z = x + y;
                        System.out.println(z);
                    }
                }
                """);
        assertThat(r.execution().status()).isEqualTo(ExecutionStatus.SUCCESS);
        List<TraceStep> steps = r.steps();

        assertThat(steps).extracting(TraceStep::line).containsExactly(3, 4, 5, 6, 7);
        assertThat(steps.get(0).event()).isEqualTo("CALL");
        assertThat(steps.get(4).event()).isEqualTo("RETURN");

        // A variable appears only after its line has run.
        assertThat(local(steps.get(0), "x")).isEmpty();
        assertThat(local(steps.get(1), "x")).get().extracting(ValueSnapshot::display).isEqualTo("5");
        assertThat(local(steps.get(3), "z")).get().extracting(ValueSnapshot::display).isEqualTo("15");
    }

    @Test
    void outputGrowsStepByStep() {
        TraceResponse r = trace("""
                public class Main {
                    public static void main(String[] args) {
                        System.out.print("ab");
                        System.out.print("é");
                        System.out.print("c");
                    }
                }
                """);
        assertThat(r.execution().stdout()).isEqualTo("abéc");
        // Character counts, not bytes: é is one character.
        assertThat(r.steps()).extracting(TraceStep::outputLength).containsExactly(0, 2, 3, 4);
    }

    @Test
    void arraySwapsAreVisible() {
        TraceResponse r = trace("""
                public class Main {
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
                    }
                }
                """);
        List<String> states = r.steps().stream()
                .filter(s -> local(s, "arr").isPresent())
                .map(s -> arrayText(s, "arr"))
                .distinct()
                .toList();
        assertThat(states).startsWith("5,2,8,1", "2,2,8,1", "2,5,8,1");
        assertThat(states).last().isEqualTo("1,2,5,8");
    }

    @Test
    void recursionShowsCallStackAndReturnValues() {
        TraceResponse r = trace("""
                public class Main {
                    static int factorial(int n) {
                        if (n == 0)
                            return 1;
                        return n * factorial(n - 1);
                    }
                    public static void main(String[] args) {
                        System.out.println(factorial(4));
                    }
                }
                """);
        assertThat(r.steps()).extracting(TraceStep::depth).contains(6); // main + factorial(4..0)

        TraceStep deepest = r.steps().stream().filter(s -> s.depth() == 6).findFirst().orElseThrow();
        assertThat(deepest.stack()).extracting(f -> f.methodName())
                .containsExactly("factorial", "factorial", "factorial", "factorial", "factorial", "main");

        List<String> returned = r.steps().stream()
                .filter(s -> s.event().equals("RETURN") && s.returnValue() != null)
                .map(s -> s.returnValue().display())
                .toList();
        assertThat(returned).containsExactly("1", "1", "2", "6", "24");
    }

    @Test
    void objectsAreCapturedWithTheirFields() {
        TraceResponse r = trace("""
                public class Main {
                    static class Node {
                        int value;
                        Node next;
                        Node(int value) { this.value = value; }
                    }
                    public static void main(String[] args) {
                        Node head = new Node(1);
                        head.next = new Node(2);
                        System.out.println(head.next.value);
                    }
                }
                """);
        TraceStep last = r.steps().get(r.steps().size() - 1);
        ValueSnapshot head = local(last, "head").orElseThrow();
        HeapObjectSnapshot headNode = last.heap().get(head.ref().toString());
        assertThat(headNode.type()).isEqualTo("Node");
        ValueSnapshot next = headNode.fields().stream().filter(f -> f.name().equals("next"))
                .findFirst().orElseThrow().value();
        assertThat(last.heap()).containsKey(next.ref().toString());
    }

    @Test
    void uncaughtExceptionIsTheLastStep() {
        TraceResponse r = trace("""
                public class Main {
                    public static void main(String[] args) {
                        int[] arr = {1, 2, 3};
                        int x = arr[3];
                    }
                }
                """);
        assertThat(r.execution().status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
        TraceStep last = r.steps().get(r.steps().size() - 1);
        assertThat(last.event()).isEqualTo("EXCEPTION");
        assertThat(last.line()).isEqualTo(4);
        assertThat(last.exceptionType()).isEqualTo("java.lang.ArrayIndexOutOfBoundsException");
    }

    @Test
    void infiniteLoopIsCutAtTheStepLimit() {
        TraceResponse r = trace("""
                public class Main {
                    public static void main(String[] args) {
                        int i = 0;
                        while (true) {
                            i++;
                        }
                    }
                }
                """);
        assertThat(r.truncated()).isTrue();
        assertThat(r.steps()).hasSize(500);
        assertThat(r.execution().status()).isEqualTo(ExecutionStatus.TIMEOUT);
    }

    @Test
    void compileErrorProducesNoSteps() {
        TraceResponse r = trace("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 5
                    }
                }
                """);
        assertThat(r.execution().status()).isEqualTo(ExecutionStatus.COMPILATION_ERROR);
        assertThat(r.steps()).isEmpty();
    }

    @Test
    void stdinWorksWhileTracing() {
        TraceResponse r = service.trace("""
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        int a = sc.nextInt();
                        System.out.println(a * 2);
                    }
                }
                """, "21");
        assertThat(r.execution().stdout().strip()).isEqualTo("42");
        assertThat(r.steps()).anySatisfy(s ->
                assertThat(local(s, "a")).get().extracting(ValueSnapshot::display).isEqualTo("21"));
    }
}
