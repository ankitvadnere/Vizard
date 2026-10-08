package com.vizard.execution;

import com.vizard.api.dto.trace.ConditionInsight;
import com.vizard.api.dto.trace.LoopInsight;
import com.vizard.api.dto.trace.PointerInsight;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.api.dto.trace.TraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 3: each step explains itself (conditions, array accesses, swaps, pointers, loops).
 */
@SpringBootTest(properties = {
        "vizard.execution.timeout-ms=3000",
        "vizard.execution.trace-timeout-ms=10000",
        "vizard.execution.max-trace-steps=500"
})
class InsightTest {

    @Autowired
    private ExecutionService service;

    private List<TraceStep> trace(String code) {
        TraceResponse r = service.trace(code, "");
        assertThat(r.steps()).isNotEmpty();
        assertThat(r.steps()).allMatch(s -> s.insight() != null);
        return r.steps();
    }

    private static List<ConditionInsight> conditions(List<TraceStep> steps) {
        return steps.stream().map(s -> s.insight().condition()).filter(Objects::nonNull).toList();
    }

    private static final String BUBBLE = """
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
            """;

    @Test
    void conditionsShowValuesAndResults() {
        List<ConditionInsight> ifs = conditions(trace(BUBBLE)).stream()
                .filter(c -> c.kind().equals("if")).toList();
        assertThat(ifs).extracting(ConditionInsight::explanation, ConditionInsight::result)
                .startsWith(
                        org.assertj.core.groups.Tuple.tuple("5 > 2", true),
                        org.assertj.core.groups.Tuple.tuple("5 > 8", false),
                        org.assertj.core.groups.Tuple.tuple("8 > 1", true));
    }

    @Test
    void comparedCellsAndSwapsAreReported() {
        List<TraceStep> steps = trace(BUBBLE);
        TraceStep firstCompare = steps.stream().filter(s -> s.line() == 6).findFirst().orElseThrow();
        assertThat(firstCompare.insight().accesses())
                .allMatch(a -> a.compared())
                .extracting(a -> a.index()).containsExactly(0, 1);

        List<String> swaps = steps.stream().map(s -> s.insight().swap()).filter(Objects::nonNull)
                .map(s -> s.first() + "," + s.second()).toList();
        assertThat(swaps).containsExactly("0,1", "2,3", "1,2", "0,1");
    }

    @Test
    void innerLoopIterationsAreCountedAndReset() {
        List<TraceStep> steps = trace(BUBBLE);
        List<Integer> innerCounts = steps.stream()
                .filter(s -> s.line() == 6)
                .map(s -> s.insight().loops().stream().filter(l -> l.line() == 5)
                        .findFirst().map(LoopInsight::iteration).orElse(0))
                .toList();
        assertThat(innerCounts).containsExactly(1, 2, 3, 1, 2, 1);
    }

    @Test
    void pointersFollowIndexVariables() {
        List<TraceStep> steps = trace("""
                public class Main {
                    static int find(int[] arr, int target) {
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
                        System.out.println(find(new int[]{1, 3, 5, 7, 9, 11, 13}, 9));
                    }
                }
                """);
        TraceStep found = steps.stream().filter(s -> s.event().equals("RETURN") && s.line() == 6)
                .findFirst().orElseThrow();
        assertThat(found.insight().condition().explanation()).isEqualTo("9 == 9");
        assertThat(found.insight().condition().result()).isTrue();
        assertThat(found.insight().pointers()).extracting(PointerInsight::variable, PointerInsight::index)
                .contains(org.assertj.core.groups.Tuple.tuple("mid", 4),
                        org.assertj.core.groups.Tuple.tuple("low", 4),
                        org.assertj.core.groups.Tuple.tuple("high", 4));
    }

    @Test
    void shortCircuitNeverReadsOutsideTheArray() {
        List<ConditionInsight> whiles = conditions(trace("""
                public class Main {
                    public static void main(String[] args) {
                        int[] arr = {4, 3};
                        for (int i = 1; i < arr.length; i++) {
                            int key = arr[i];
                            int j = i - 1;
                            while (j >= 0 && arr[j] > key) {
                                arr[j + 1] = arr[j];
                                j--;
                            }
                            arr[j + 1] = key;
                        }
                    }
                }
                """)).stream().filter(c -> c.kind().equals("while")).toList();
        assertThat(whiles).extracting(ConditionInsight::explanation, ConditionInsight::result)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("0 >= 0 && 4 > 3", true),
                        org.assertj.core.groups.Tuple.tuple("-1 >= 0 && arr[j] > 3", false));
    }

    @Test
    void oneLineLoopsStepThroughEveryIteration() {
        List<TraceStep> steps = trace("""
                public class Main {
                    public static void main(String[] args) {
                        int sum = 0;
                        for (int i = 0; i < 3; i++) sum += i;
                        System.out.println(sum);
                    }
                }
                """);
        assertThat(steps.stream().filter(s -> s.line() == 4).count()).isGreaterThanOrEqualTo(4);
        assertThat(conditions(steps)).extracting(ConditionInsight::explanation)
                .contains("0 < 3", "1 < 3", "2 < 3", "3 < 3");
    }

    @Test
    void conditionsWithMethodCallsUseWhatActuallyHappened() {
        List<ConditionInsight> ifs = conditions(trace("""
                public class Main {
                    static boolean isEven(int n) {
                        return n % 2 == 0;
                    }
                    public static void main(String[] args) {
                        int n = 4;
                        if (isEven(n)) {
                            System.out.println("even");
                        } else {
                            System.out.println("odd");
                        }
                    }
                }
                """)).stream().filter(c -> c.kind().equals("if")).toList();
        assertThat(ifs).hasSize(1);
        assertThat(ifs.get(0).result()).isTrue();
    }
}
