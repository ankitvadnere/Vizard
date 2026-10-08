package com.vizard.execution;

import com.vizard.api.dto.Example;
import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.api.dto.trace.ExecutionStats;
import com.vizard.api.dto.trace.RangeInsight;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 4: operation counts, active ranges and complexity for the sorting and searching
 * examples. Expected counts were checked against independent implementations of each algorithm.
 */
@SpringBootTest
class StatisticsTest {

    @Autowired
    private ExampleCatalog catalog;

    @Autowired
    private ExecutionService service;

    private TraceResponse trace(String exampleId) {
        Example e = catalog.all().stream().filter(x -> x.id().equals(exampleId)).findFirst().orElseThrow();
        return service.trace(e.code(), "");
    }

    private static ExecutionStats last(TraceResponse r) {
        return r.steps().get(r.steps().size() - 1).stats();
    }

    @Test
    void bubbleSortCountsMatchTheTheory() {
        TraceResponse r = trace("bubble-sort");          // {5, 2, 8, 1}
        ExecutionStats s = last(r);
        assertThat(s.comparisons()).isEqualTo(6);        // n(n-1)/2 for n = 4
        assertThat(s.swaps()).isEqualTo(4);              // the number of inversions
        assertThat(s.maxDepth()).isEqualTo(1);

        AlgorithmMatch match = r.analysis().algorithms().get(0);
        assertThat(r.analysis().input().n()).isEqualTo(4);
        assertThat(match.bound().value()).isEqualTo(6L);
        assertThat(match.complexity().worst()).isEqualTo("O(n²)");
    }

    @Test
    void countsOnlyGrowAndRewindWithTheSteps() {
        List<TraceStep> steps = trace("bubble-sort").steps();
        for (int k = 1; k < steps.size(); k++) {
            ExecutionStats before = steps.get(k - 1).stats();
            ExecutionStats now = steps.get(k).stats();
            assertThat(now.steps()).isEqualTo(k + 1);
            assertThat(now.comparisons()).isGreaterThanOrEqualTo(before.comparisons());
            assertThat(now.swaps()).isGreaterThanOrEqualTo(before.swaps());
            assertThat(now.arrayReads()).isGreaterThanOrEqualTo(before.arrayReads());
        }
        assertThat(steps.get(0).stats().comparisons()).isZero();
    }

    @Test
    void selectionSortAlwaysMakesExactlyNChoose2Comparisons() {
        ExecutionStats s = last(trace("selection-sort")); // {29, 10, 14, 37, 13}
        assertThat(s.comparisons()).isEqualTo(10);
        assertThat(s.swaps()).isEqualTo(3);                // one of the 4 passes swaps a cell with itself
    }

    @Test
    void mergeSortNeverSwapsAndStaysWithinItsBound() {
        TraceResponse r = trace("merge-sort");           // {38, 27, 43, 3, 9, 82, 10}
        ExecutionStats s = last(r);
        assertThat(s.swaps()).isZero();                  // copying back from temp is not a swap
        assertThat(s.comparisons()).isEqualTo(14);
        assertThat((long) s.comparisons()).isLessThanOrEqualTo(r.analysis().algorithms().get(0).bound().value());
        assertThat(s.maxDepth()).isEqualTo(5);           // main → mergeSort ×3 → merge
    }

    @Test
    void quickSortCountsMatchAnIndependentRun() {
        ExecutionStats s = last(trace("quick-sort"));     // {10, 80, 30, 90, 40, 50, 70}, Lomuto partition
        assertThat(s.comparisons()).isEqualTo(13);
        assertThat(s.swaps()).isEqualTo(5);
    }

    @Test
    void binarySearchRangeShrinks() {
        TraceResponse r = trace("binary-search");        // find 11 in {1, 3, 5, 7, 9, 11, 13}
        List<String> ranges = r.steps().stream()
                .flatMap(s -> s.insight().ranges().stream())
                .map(g -> g.fromVariable() + "=" + g.from() + ".." + g.toVariable() + "=" + g.to())
                .distinct().toList();
        assertThat(ranges).containsExactly("low=0..high=6", "low=4..high=6");
        assertThat(last(r).loopIterations()).isEqualTo(2);
        assertThat(last(r).comparisons()).isEqualTo(3);
    }

    @Test
    void eachRecursiveCallOfQuickSortHasItsOwnRange() {
        List<RangeInsight> ranges = trace("quick-sort").steps().stream()
                .filter(s -> s.stack().get(0).methodName().equals("quickSort"))
                .flatMap(s -> s.insight().ranges().stream())
                .distinct().toList();
        assertThat(ranges).extracting(RangeInsight::from, RangeInsight::to)
                .startsWith(org.assertj.core.groups.Tuple.tuple(0, 6), org.assertj.core.groups.Tuple.tuple(0, 3));
    }

    @Test
    void theMergeStepHasNoMisleadingRange() {
        assertThat(trace("merge-sort").steps().stream()
                .filter(s -> s.stack().get(0).methodName().equals("merge"))
                .flatMap(s -> s.insight().ranges().stream())
                .filter(Objects::nonNull)).isEmpty();
    }
}
