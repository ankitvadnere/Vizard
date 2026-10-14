package com.vizard.execution;

import com.vizard.api.dto.trace.GraphEdge;
import com.vizard.api.dto.trace.GraphInsight;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.VertexValue;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 6: the graph and the traversal state are rebuilt at every step from the real run.
 * Expected values were worked out by hand from each example's graph.
 */
@SpringBootTest
class GraphTraceTest {

    @Autowired
    private ExecutionService service;

    private final ExampleCatalog examples = new ExampleCatalog();

    private TraceResponse trace(String id) throws Exception {
        return service.trace(examples.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().code(), "");
    }

    private static GraphInsight lastGraph(TraceResponse r) {
        return r.steps().stream().map(TraceStep::insight).filter(Objects::nonNull)
                .map(i -> i.graph()).filter(Objects::nonNull).reduce((a, b) -> b).orElseThrow();
    }

    private static List<String> tree(GraphInsight g) {
        return g.treeEdges().stream().map(e -> e.from() + ">" + e.to()).sorted().toList();
    }

    @Test
    void bfsVisitsLevelByLevelAndExaminesEachEdgeTwice() throws Exception {
        TraceResponse r = trace("bfs");
        GraphInsight g = lastGraph(r);
        assertThat(g.directed()).isFalse();
        assertThat(g.vertices()).containsExactly("0", "1", "2", "3", "4", "5");
        assertThat(g.edges()).hasSize(7);
        assertThat(g.order()).containsExactly("0", "1", "2", "3", "4", "5");
        assertThat(tree(g)).containsExactly("0>1", "0>2", "1>3", "1>4", "3>5");
        var stats = r.steps().get(r.steps().size() - 1).stats();
        assertThat(stats.edgesExamined()).isEqualTo(14); // 2E for an undirected graph
        assertThat(stats.verticesVisited()).isEqualTo(6);
        assertThat(r.analysis().algorithms().get(0).bound().value()).isEqualTo(14L);
    }

    @Test
    void theGraphIsDrawnWhileMainBuildsIt() throws Exception {
        TraceResponse r = trace("bfs");
        List<Integer> edgeCounts = r.steps().stream()
                .map(s -> s.insight() == null || s.insight().graph() == null ? -1 : s.insight().graph().edges().size())
                .filter(n -> n >= 0).distinct().toList();
        assertThat(edgeCounts).containsSubsequence(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void recursiveDfsShowsTheRecursionPath() throws Exception {
        TraceResponse r = trace("dfs-recursive");
        GraphInsight g = lastGraph(r);
        assertThat(g.order()).containsExactly("0", "1", "3", "5", "4", "2");
        assertThat(tree(g)).containsExactly("0>1", "1>3", "3>5", "4>2", "5>4");
        int deepest = r.steps().stream().map(TraceStep::insight).filter(Objects::nonNull)
                .map(i -> i.graph()).filter(Objects::nonNull).mapToInt(x -> x.onStack().size()).max().orElse(0);
        assertThat(deepest).isEqualTo(6); // 0 → 1 → 3 → 5 → 4 → 2
    }

    @Test
    void iterativeDfsUsesTheStackOrder() throws Exception {
        GraphInsight g = lastGraph(trace("dfs-iterative"));
        assertThat(g.order()).containsExactly("0", "2", "4", "1", "3", "5");
        assertThat(tree(g)).containsExactly("0>2", "1>3", "2>4", "3>5", "4>1");
    }

    @Test
    void dijkstraWithAHeapRelaxesSixTimesAndBuildsTheShortestPathTree() throws Exception {
        TraceResponse r = trace("dijkstra");
        GraphInsight g = lastGraph(r);
        assertThat(g.weighted()).isTrue();
        assertThat(g.edges()).contains(new GraphEdge("0", "1", "4"));
        assertThat(g.distances()).extracting(VertexValue::value).containsExactly("0", "3", "1", "4", "7");
        assertThat(g.order()).containsExactly("0", "2", "1", "3", "4");
        assertThat(tree(g)).containsExactly("0>2", "1>3", "2>1", "3>4");
        var stats = r.steps().get(r.steps().size() - 1).stats();
        assertThat(stats.relaxations()).isEqualTo(6);
        assertThat(stats.edgesExamined()).isEqualTo(12);
        // The comparator lambda runs inside PriorityQueue: it is not traced as the user's control flow.
        assertThat(r.steps()).noneMatch(s -> s.stack().stream().anyMatch(f -> f.methodName().startsWith("lambda$")));
    }

    @Test
    void dijkstraOnAMatrixChecksEveryCellOfEachProcessedRow() throws Exception {
        TraceResponse r = trace("dijkstra-matrix");
        GraphInsight g = lastGraph(r);
        assertThat(g.representation()).isEqualTo("adjacency-matrix");
        assertThat(g.edges()).hasSize(6);
        assertThat(g.distances()).extracting(VertexValue::value).containsExactly("0", "3", "1", "4", "7");
        var stats = r.steps().get(r.steps().size() - 1).stats();
        assertThat(stats.edgesExamined()).isEqualTo(20); // (V − 1) rows × V cells
        assertThat(stats.relaxations()).isEqualTo(6);
        assertThat(r.steps().stream().map(TraceStep::insight).filter(Objects::nonNull)
                .map(i -> i.graph()).filter(Objects::nonNull)
                .anyMatch(x -> x.distances().stream().anyMatch(d -> d.value().equals("∞")))).isTrue();
    }

    @Test
    void forEachLoopsCountEveryIteration() throws Exception {
        TraceResponse r = trace("stack-brackets");
        // "{[()()]}" has 8 characters; "([)]" stops at its third.
        assertThat(r.steps().get(r.steps().size() - 1).stats().loopIterations()).isEqualTo(11);
    }
}
