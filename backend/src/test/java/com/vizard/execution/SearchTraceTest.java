package com.vizard.execution;

import com.vizard.api.dto.analysis.SearchNode;
import com.vizard.api.dto.analysis.SearchTree;
import com.vizard.api.dto.trace.ExecutionStats;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 7: the state-space tree is rebuilt from the real calls. Expected counts were worked out
 * by hand (N-Queens, permutations) or by an independent Python run (subset sum).
 */
@SpringBootTest
class SearchTraceTest {

    @Autowired
    private ExecutionService service;

    private final ExampleCatalog examples = new ExampleCatalog();

    private TraceResponse trace(String id) throws Exception {
        return service.trace(examples.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().code(), "");
    }

    private static long count(SearchTree t, String kind) {
        return t.nodes().stream().filter(n -> n.kind().equals(kind)).count();
    }

    private static ExecutionStats last(TraceResponse r) {
        return r.steps().get(r.steps().size() - 1).stats();
    }

    @Test
    void nQueensFindsTheFirstSolutionAfterNineCalls() throws Exception {
        TraceResponse r = trace("n-queens");
        SearchTree t = r.analysis().searchTrees().get(0);
        assertThat(t.problem()).isEqualTo("n-queens");
        assertThat(t.depthBound()).isEqualTo(4L);
        assertThat(t.branching()).isEqualTo(4L);
        assertThat(count(t, "explored") + count(t, "dead-end") + count(t, "solution")).isEqualTo(9);
        assertThat(count(t, "rejected")).isEqualTo(18);
        assertThat(count(t, "dead-end")).isEqualTo(2);
        assertThat(count(t, "solution")).isEqualTo(1);
        // The solution is reached by the columns 1, 3, 0, 2 (one per row).
        SearchNode solution = t.nodes().stream().filter(n -> n.kind().equals("solution")).findFirst().orElseThrow();
        List<String> path = new java.util.ArrayList<>();
        for (SearchNode n = solution; n.parent() != null; n = t.nodes().get(n.parent())) {
            path.add(0, n.label());
        }
        assertThat(path).containsExactly("1", "3", "0", "2");
        ExecutionStats s = last(r);
        assertThat(s.nodesExplored()).isEqualTo(9);
        assertThat(s.branchesPruned()).isEqualTo(18);
        assertThat(s.solutionsFound()).isEqualTo(1);
        assertThat(s.backtracks()).isEqualTo(8);
        assertThat(r.analysis().algorithms().get(0).bound().value()).isEqualTo(341L);
    }

    @Test
    void permutationsVisitEveryPartialArrangementOnce() throws Exception {
        TraceResponse r = trace("permutations");
        SearchTree t = r.analysis().searchTrees().get(0);
        assertThat(t.nodes().stream().filter(n -> !n.kind().equals("rejected")).count()).isEqualTo(16);
        assertThat(count(t, "solution")).isEqualTo(6);
        assertThat(count(t, "rejected")).isEqualTo(15); // the used[] check
        assertThat(last(r).solutionsFound()).isEqualTo(6);
        // Branches are labelled with the element chosen (nums[i]), not the index.
        assertThat(t.nodes().get(1).label()).isEqualTo("1");
        assertThat(t.nodes().get(1).choice()).isEqualTo("i = 0 (nums[i] = 1)");
    }

    @Test
    void subsetSumMatchesAnIndependentCount() throws Exception {
        TraceResponse r = trace("subset-sum");
        SearchTree t = r.analysis().searchTrees().get(0);
        assertThat(t.kind()).isEqualTo("binary");
        assertThat(t.nodes()).hasSize(27);
        assertThat(count(t, "pruned")).isEqualTo(5);
        assertThat(count(t, "solution")).isEqualTo(2);
        assertThat(count(t, "dead-end")).isEqualTo(7);
        assertThat(t.nodes().get(1).label()).isEqualTo("+3");
        assertThat(t.nodes().get(1).choice()).isEqualTo("take a[i] = 3");
        assertThat(r.analysis().algorithms().get(0).bound().value()).isEqualTo(31L);
    }

    @Test
    void graphColouringStopsAtTheFirstValidColouring() throws Exception {
        TraceResponse r = trace("graph-coloring");
        assertThat(r.execution().stdout()).contains("[1, 2, 3, 2]");
        SearchTree t = r.analysis().searchTrees().get(0);
        assertThat(count(t, "rejected")).isEqualTo(4);
        assertThat(count(t, "solution")).isEqualTo(1);
        assertThat(t.nodes().stream().filter(n -> n.kind().equals("rejected")).map(SearchNode::choice).toList())
                .containsExactly("c = 1", "c = 1", "c = 2", "c = 1");
    }

    @Test
    void aRejectedChoiceAppearsOnlyOnceTheCheckHasAnswered() throws Exception {
        TraceResponse r = trace("graph-coloring");
        SearchTree t = r.analysis().searchTrees().get(0);
        SearchNode rejected = t.nodes().stream().filter(n -> n.kind().equals("rejected")).findFirst().orElseThrow();
        // At that step the program is back in colour(), after isSafe() returned false.
        assertThat(r.steps().get(rejected.firstStep()).stack().get(0).methodName()).isEqualTo("colour");
    }
}
