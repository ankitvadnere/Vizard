package com.vizard.execution.algorithms;

import com.github.javaparser.StaticJavaParser;
import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Branch and bound recognisers: live-node lists, and depth-first searches that keep a best-so-far bound. */
class BnbAlgorithmsTest {

    private static List<Detection> detect(String id) {
        String code = new ExampleCatalog().all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().code();
        return AlgorithmDetector.detect(StaticJavaParser.parse(code));
    }

    @Test
    void bestFirstKnapsack() {
        List<Detection> found = detect("knapsack-bnb");
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.KNAPSACK_BNB);
        assertThat(found.get(0).method()).isEqualTo("knapsack");
        assertThat(found.get(0).traits()).contains("priority");
        assertThat(String.join(" ", found.get(0).evidence())).contains("best-first").contains("fractional");
    }

    @Test
    void tspWithAndWithoutTheBound() {
        List<Detection> found = detect("tsp");
        assertThat(found).extracting(Detection::algorithm)
                .containsExactlyInAnyOrder(Algorithm.TSP_BACKTRACKING, Algorithm.TSP_BNB);
        Detection bnb = found.stream().filter(d -> d.algorithm() == Algorithm.TSP_BNB).findFirst().orElseThrow();
        assertThat(bnb.method()).isEqualTo("tourBnB");
        assertThat(bnb.traits()).contains(BacktrackAlgorithms.BRANCH_AND_BOUND);
        // The neighbour loop over the distance matrix is not reported as a graph search.
        assertThat(found).extracting(Detection::algorithm).doesNotContain(Algorithm.DFS);
    }

    @Test
    void jobAssignmentWithALowerBoundHelper() {
        List<Detection> found = detect("job-assignment");
        assertThat(found).extracting(Detection::algorithm).containsExactly(Algorithm.ASSIGNMENT_BNB);
        assertThat(found.get(0).method()).isEqualTo("assign");
    }

    @Test
    void complexityIsTheWorstCaseNotTheRun() {
        AlgorithmMatch m = AlgorithmCatalog.describe(detect("tsp").stream()
                .filter(d -> d.algorithm() == Algorithm.TSP_BNB).findFirst().orElseThrow(), 5);
        assertThat(m.complexity().worst()).isEqualTo("O(n!)");
        assertThat(m.complexity().note()).contains("worst case");
        AlgorithmMatch k = AlgorithmCatalog.describe(detect("knapsack-bnb").get(0), 4);
        assertThat(k.complexity().worst()).isEqualTo("O(2ⁿ)");
        assertThat(k.category()).isEqualTo("Branch and bound");
    }
}
