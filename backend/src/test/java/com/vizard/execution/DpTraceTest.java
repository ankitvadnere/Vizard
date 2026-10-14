package com.vizard.execution;

import com.vizard.api.dto.analysis.DpTable;
import com.vizard.api.dto.analysis.SearchTree;
import com.vizard.api.dto.trace.DpInsight;
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
 * Milestone 8: DP tables fill cell by cell and recursion trees show memo hits and repeated calls.
 * Expected values come from an independent Python run of the same algorithms.
 */
@SpringBootTest
class DpTraceTest {

    @Autowired
    private ExecutionService service;

    private final ExampleCatalog examples = new ExampleCatalog();

    private TraceResponse trace(String id) throws Exception {
        return service.trace(examples.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().code(), "");
    }

    private static SearchTree tree(TraceResponse r, String method) {
        return r.analysis().searchTrees().stream().filter(t -> t.method().equals(method)).findFirst().orElseThrow();
    }

    private static long count(SearchTree t, String kind) {
        return t.nodes().stream().filter(n -> n.kind().equals(kind)).count();
    }

    private static List<DpInsight> insights(TraceResponse r) {
        return r.steps().stream().map(TraceStep::insight).filter(Objects::nonNull).map(i -> i.dp())
                .filter(Objects::nonNull).toList();
    }

    @Test
    void fibonacciThreeWaysShowsTheWorkMemoisationSaves() throws Exception {
        TraceResponse r = trace("fibonacci");
        assertThat(r.execution().stdout()).contains("naive:  8 (25 calls)", "memo:   8 (11 calls)");
        SearchTree naive = tree(r, "fibNaive");
        assertThat(naive.kind()).isEqualTo("recursion");
        assertThat(naive.nodes()).hasSize(25);
        assertThat(count(naive, "repeat")).isEqualTo(18); // only fib(0)..fib(6) are different: 25 − 7
        SearchTree memo = tree(r, "fibMemo");
        assertThat(memo.kind()).isEqualTo("memo");
        assertThat(memo.nodes()).hasSize(11);
        assertThat(count(memo, "hit")).isEqualTo(3);
        DpTable table = r.analysis().dpTables().stream().filter(t -> t.table().equals("dp")).findFirst().orElseThrow();
        assertThat(table.writes()).extracting(w -> w.col()).containsExactly(0, 1, 2, 3, 4, 5, 6);
        var stats = r.steps().get(r.steps().size() - 1).stats();
        assertThat(stats.memoHits()).isEqualTo(3);
        assertThat(stats.repeatedCalls()).isEqualTo(18);
    }

    @Test
    void knapsackFillsTheTableRowByRow() throws Exception {
        TraceResponse r = trace("knapsack");
        assertThat(r.execution().stdout()).contains("45");
        DpTable t = r.analysis().dpTables().get(0);
        assertThat(t.rows()).isEqualTo(4);
        assertThat(t.cols()).isEqualTo(6);
        assertThat(r.steps().get(r.steps().size() - 1).stats().cellsFilled()).isEqualTo(18); // 3 items × 6 capacities
        DpInsight take = insights(r).stream().filter(d -> d.cell().equals(List.of(3, 5)) && d.reads().size() == 2)
                .findFirst().orElseThrow();
        assertThat(take.reads()).containsExactly(List.of(3, 5), List.of(2, 1));
        assertThat(take.values()).isEqualTo("max(35, 15 + 30)");
        assertThat(take.result()).isEqualTo("45");
    }

    @Test
    void lcsTableIsLabelledWithTheLetters() throws Exception {
        TraceResponse r = trace("lcs");
        DpTable t = r.analysis().dpTables().get(0);
        assertThat(t.rowLabels()).containsExactly("", "A", "B", "C", "B");
        assertThat(t.colLabels()).containsExactly("", "B", "D", "C", "A", "B");
        assertThat(r.execution().stdout()).contains("LCS length: 3");
    }

    @Test
    void matrixChainShowsTheSplitCost() throws Exception {
        TraceResponse r = trace("matrix-chain");
        assertThat(r.execution().stdout()).contains("3000");
        assertThat(insights(r)).anySatisfy(d -> {
            assertThat(d.label()).isEqualTo("cost");
            assertThat(d.values()).isEqualTo("0 + 0 + 20 * 5 * 30");
            assertThat(d.result()).isEqualTo("3000");
        });
        assertThat(r.steps().get(r.steps().size() - 1).stats().cellsFilled()).isEqualTo(6); // the upper triangle
    }

    @Test
    void memoisedKnapsackAnswersRepeatedStatesFromTheMemo() throws Exception {
        TraceResponse r = trace("knapsack-memo");
        assertThat(r.execution().stdout()).contains("55");
        SearchTree t = tree(r, "best");
        assertThat(t.nodes()).hasSize(22);
        assertThat(count(t, "hit")).isEqualTo(3);
        assertThat(t.nodes().get(0).label()).isEqualTo("0, 4"); // best(i = 0, w = 4)
        assertThat(r.steps().get(r.steps().size() - 1).stats().cellsFilled()).isEqualTo(11);
    }

    @Test
    void coinChangeAndEditDistance() throws Exception {
        TraceResponse coins = trace("coin-change");
        assertThat(coins.execution().stdout()).contains("Fewest coins for 6: 2");
        assertThat(coins.analysis().dpTables().get(0).writes()).hasSize(19);
        TraceResponse edit = trace("edit-distance");
        assertThat(edit.execution().stdout()).contains("Edit distance: 2");
        assertThat(edit.steps().get(edit.steps().size() - 1).stats().cellsFilled()).isEqualTo(16);
    }
}
