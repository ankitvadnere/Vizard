package com.vizard.execution.algorithms;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.vizard.execution.insight.BnbModelBuilder;
import com.vizard.execution.insight.CodeModel.BnbSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Branch and bound with a live-node list (see {@link BnbModelBuilder}). The order of the list names
 * the strategy: a priority queue on the bound is best-first (least-cost) search, a queue is FIFO
 * branch and bound, a stack LIFO. A fractional bound function names the 0/1 knapsack.
 */
final class BnbAlgorithms {

    private BnbAlgorithms() {
    }

    static Optional<Detection> detect(MethodDeclaration m, CompilationUnit cu) {
        Optional<BnbSpec> found = BnbModelBuilder.find(m, cu);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BnbSpec s = found.get();
        String strategy = switch (s.order()) {
            case "priority" -> "best-first: the live node with the " + (s.maximise() ? "highest" : "lowest")
                    + " bound is expanded next (" + s.frontier() + " is a priority queue)";
            case "stack" -> "LIFO: the newest live node is expanded next (" + s.frontier() + " is a stack)";
            default -> "FIFO: live nodes are expanded in the order they were made (" + s.frontier() + " is a queue)";
        };
        List<String> evidence = new ArrayList<>();
        evidence.add("Live nodes (objects of " + s.nodeClass() + ") wait in " + s.frontier() + "; the loop takes one out "
                + "as " + s.current() + " and makes new nodes from it: branching.");
        evidence.add("Each node's " + s.boundField() + " is compared with " + s.bestVar() + ", the best solution so far; "
                + "a node that cannot beat it is dropped: bounding.");
        evidence.add("Order: " + strategy + ".");
        if (s.valueField() != null) {
            evidence.add(s.bestVar() + " is updated from a node's " + s.valueField() + " whenever a better solution "
                    + "appears.");
        }
        Algorithm a = s.problem().equals("knapsack") ? Algorithm.KNAPSACK_BNB : Algorithm.BRANCH_AND_BOUND;
        if (a == Algorithm.KNAPSACK_BNB) {
            evidence.add("The bound function fills the remaining capacity greedily and takes a fraction of the next "
                    + "item: the fractional-knapsack upper bound.");
        }
        return Optional.of(new Detection(a, m.getNameAsString(), m.getBegin().map(p -> p.line).orElse(0),
                List.copyOf(evidence), Set.of(s.order())));
    }
}
