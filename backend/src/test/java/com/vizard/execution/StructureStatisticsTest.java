package com.vizard.execution;

import com.vizard.api.dto.Example;
import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.api.dto.analysis.StructureUse;
import com.vizard.api.dto.trace.ExecutionStats;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Milestone 5: exact structure-operation counts, measured tree facts and per-operation costs. */
@SpringBootTest
class StructureStatisticsTest {

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
    void bracketCheckerPushesAndPops() {
        // "{[()()]}" pushes 4 and pops 4; "([)]" pushes 2, pops 1 and stops at the mismatch
        ExecutionStats s = last(trace("stack-brackets"));
        assertThat(s.pushes()).isEqualTo(6);
        assertThat(s.pops()).isEqualTo(5);
        assertThat(s.lookups()).isZero(); // isEmpty() is not an operation on the contents
    }

    @Test
    void queueOfBinaryNumbers() {
        ExecutionStats s = last(trace("queue-binary")); // 1 + 2 × 5 offers, 5 polls
        assertThat(s.enqueues()).isEqualTo(11);
        assertThat(s.dequeues()).isEqualTo(5);
    }

    @Test
    void hashMapPutsAndLookups() {
        TraceResponse r = trace("hashmap-frequency");
        assertThat(last(r).inserts()).isEqualTo(6);  // put per word
        assertThat(last(r).lookups()).isEqualTo(7);  // getOrDefault per word + one get
        StructureUse count = r.analysis().structures().get(0);
        assertThat(count.variable()).isEqualTo("count");
        assertThat(count.role()).isEqualTo("map");
        assertThat(count.operations()).extracting(o -> o.method() + " " + o.count() + " " + o.cost())
                .containsExactly("getOrDefault 6 O(1) average", "put 6 O(1) average", "get 1 O(1) average");
    }

    @Test
    void priorityQueueCostsLogN() {
        StructureUse heap = trace("priority-queue").analysis().structures().get(0);
        assertThat(heap.operations()).extracting(o -> o.method() + " " + o.count() + " " + o.cost())
                .containsExactly("offer 5 O(log n)", "poll 5 O(log n)");
    }

    @Test
    void bstComparisonsAndMeasuredHeight() {
        TraceResponse r = trace("bst");
        // counted by hand: 15 key comparisons in the 6 non-trivial inserts, 5 + 6 in the two searches
        assertThat(last(r).comparisons()).isEqualTo(26);
        AlgorithmMatch insert = r.analysis().algorithms().get(0);
        assertThat(insert.id()).isEqualTo("bst-insert");
        assertThat(insert.measures()).extracting(m -> m.label() + "=" + m.value())
                .containsExactly("Nodes n=7", "Height h of this tree=2", "Height if balanced, ⌊log₂n⌋=2",
                        "Height of a chain, n − 1=6");
    }

    @Test
    void traversalsVisitEveryNodeOnce() {
        TraceResponse r = trace("tree-traversals");
        assertThat(r.execution().stdout()).contains("Preorder:    1 2 4 5 3", "Inorder:     4 2 5 1 3",
                "Postorder:   4 5 2 3 1", "Level order: 1 2 3 4 5");
        assertThat(last(r).methodCalls()).isEqualTo(39); // 5 constructors + 3 × (2n + 1) + 1
        assertThat(last(r).enqueues()).isEqualTo(5);
        assertThat(r.analysis().algorithms()).extracting(AlgorithmMatch::id)
                .containsExactly("preorder", "inorder", "postorder", "level-order");
    }

    @Test
    void reversalMeasuresTheList() {
        AlgorithmMatch reversal = trace("linked-list-reverse").analysis().algorithms().get(0);
        assertThat(reversal.complexity().space()).isEqualTo("O(1)");
        assertThat(reversal.measures()).extracting(m -> m.value()).containsExactly("4");
    }
}
