package com.vizard.execution;

import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.api.dto.analysis.SearchNode;
import com.vizard.api.dto.analysis.SearchTree;
import com.vizard.api.dto.trace.ExecutionStats;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 9: branch-and-bound trees rebuilt from the real run. Expected numbers come from an
 * independent Python run of the same programs (knapsack: best 65 with 9 nodes generated and 4
 * expanded; TSP: 65 calls without the bound, 37 with it; assignment: 9 calls, cost 13).
 */
@SpringBootTest
class BnbTraceTest {

    @Autowired
    private ExecutionService service;

    private final ExampleCatalog examples = new ExampleCatalog();

    private TraceResponse trace(String id) throws Exception {
        return service.trace(examples.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().code(), "");
    }

    private static long count(SearchTree t, String kind) {
        return t.nodes().stream().filter(n -> n.kind().equals(kind)).count();
    }

    private static long calls(SearchTree t) {
        return t.nodes().stream().filter(n -> !n.kind().equals("rejected")).count();
    }

    private static SearchTree tree(TraceResponse r, String method) {
        return r.analysis().searchTrees().stream().filter(t -> t.method().equals(method)).findFirst().orElseThrow();
    }

    @Test
    void bestFirstKnapsackTree() throws Exception {
        TraceResponse r = trace("knapsack-bnb");
        assertThat(r.execution().stdout()).contains("Best value: 65");
        SearchTree t = tree(r, "knapsack");
        assertThat(t.kind()).isEqualTo("bnb");
        assertThat(t.nodes()).hasSize(9);
        assertThat(count(t, "expanded")).isEqualTo(4);
        assertThat(count(t, "pruned")).isEqualTo(5);
        assertThat(t.bestName()).isEqualTo("best");
        assertThat(t.bestUpdates()).isNotEmpty();
        assertThat(t.bestUpdates().get(t.bestUpdates().size() - 1).value()).isEqualTo("65");
        // Every node but the root has a parent that was expanded; each was live before it was taken out.
        for (SearchNode n : t.nodes()) {
            if (n.parent() != null) {
                assertThat(t.nodes().get(n.parent()).kind()).isEqualTo("expanded");
            }
            if (n.liveStep() != null && n.pollStep() != null) {
                assertThat(n.liveStep() < n.pollStep()).isTrue();
            }
        }
        assertThat(t.nodes().get(0).label()).isEqualTo("76"); // root bound: 40 + 42·(6/7) = 76
        ExecutionStats s = r.steps().get(r.steps().size() - 1).stats();
        assertThat(s.nodesGenerated()).isEqualTo(9);
        assertThat(s.nodesExpanded()).isEqualTo(4);
        assertThat(s.prunedByBound()).isEqualTo(5);
        AlgorithmMatch m = r.analysis().algorithms().get(0);
        assertThat(m.bound().metric()).isEqualTo("nodesGenerated");
        assertThat(m.bound().value()).isEqualTo(31L);
    }

    @Test
    void theBoundCutsTheTspTree() throws Exception {
        TraceResponse r = trace("tsp");
        assertThat(r.execution().stdout()).contains("shortest tour = 24");
        assertThat(calls(tree(r, "tour"))).isEqualTo(65);
        assertThat(calls(tree(r, "tourBnB"))).isEqualTo(37);
        SearchTree bnb = tree(r, "tourBnB");
        assertThat(bnb.bestName()).isEqualTo("best");
        assertThat(bnb.bestUpdates().get(bnb.bestUpdates().size() - 1).value()).isEqualTo("24");
        // Two searches share one counter, so no single bound meter.
        for (AlgorithmMatch a : r.analysis().algorithms()) {
            assertThat(a.bound()).isNull();
        }
    }

    @Test
    void jobAssignmentWithALowerBound() throws Exception {
        TraceResponse r = trace("job-assignment");
        assertThat(r.execution().stdout()).contains("Cheapest assignment: 13");
        SearchTree t = tree(r, "assign");
        assertThat(t.problem()).isEqualTo("assignment");
        assertThat(calls(t)).isEqualTo(9);
        assertThat(t.bestUpdates().get(t.bestUpdates().size() - 1).value()).isEqualTo("13");
        AlgorithmMatch m = r.analysis().algorithms().get(0);
        assertThat(m.bound().value()).isEqualTo(65L);
    }
}
