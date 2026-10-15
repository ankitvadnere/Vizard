package com.vizard.execution.insight;

import com.github.javaparser.StaticJavaParser;
import com.vizard.examples.ExampleCatalog;
import com.vizard.execution.insight.CodeModel.BnbSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The live-node loop of branch and bound: what is taken out, what is compared with the best, and look-alikes. */
class BnbModelBuilderTest {

    private static List<BnbSpec> specs(String source) {
        return BnbModelBuilder.build(StaticJavaParser.parse(source));
    }

    private static String example(String id) {
        return new ExampleCatalog().all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().code();
    }

    @Test
    void bestFirstKnapsack() {
        List<BnbSpec> found = specs(example("knapsack-bnb"));
        assertThat(found).hasSize(1);
        BnbSpec s = found.get(0);
        assertThat(s.method()).isEqualTo("knapsack");
        assertThat(s.frontier()).isEqualTo("pq");
        assertThat(s.order()).isEqualTo("priority");
        assertThat(s.nodeClass()).isEqualTo("Node");
        assertThat(s.current()).isEqualTo("u");
        assertThat(s.boundField()).isEqualTo("bound");
        assertThat(s.bestVar()).isEqualTo("best");
        assertThat(s.valueField()).isEqualTo("profit");
        assertThat(s.levelField()).isEqualTo("level");
        assertThat(s.maximise()).isTrue();
        assertThat(s.problem()).isEqualTo("knapsack");
        assertThat(s.pruneLines()).isNotEmpty();
        assertThat(s.addGuards().size()).isEqualTo(2);
    }

    @Test
    void fifoQueueOfNodes() {
        List<BnbSpec> found = specs("""
                import java.util.*;
                class Main {
                    static class N { int level; int cost; int bound; N(int l, int c) { level = l; cost = c; } }
                    static int solve(int n) {
                        Queue<N> q = new ArrayDeque<>();
                        q.add(new N(0, 0));
                        int best = Integer.MAX_VALUE;
                        while (!q.isEmpty()) {
                            N u = q.poll();
                            if (u.bound >= best) continue;
                            if (u.level == n) { best = Math.min(best, u.cost); continue; }
                            for (int c = 1; c <= 2; c++) {
                                N v = new N(u.level + 1, u.cost + c);
                                v.bound = v.cost + (n - v.level);
                                if (v.bound < best) q.add(v);
                            }
                        }
                        return best;
                    }
                }
                """);
        assertThat(found).hasSize(1);
        BnbSpec s = found.get(0);
        assertThat(s.order()).isEqualTo("queue");
        assertThat(s.maximise()).isFalse();
        assertThat(s.problem().equals("knapsack")).isFalse();
    }

    @Test
    void dijkstraIsNotBranchAndBound() {
        assertThat(specs(example("dijkstra"))).isEmpty();
        assertThat(specs(example("bfs"))).isEmpty();
    }
}
