package com.vizard.execution.insight;

import com.vizard.api.dto.analysis.SearchNode;
import com.vizard.api.dto.analysis.SearchTree;
import com.vizard.api.dto.trace.FrameSnapshot;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.VariableSnapshot;
import com.vizard.execution.insight.CodeModel.RecursionSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recursion tree of a memoised or plain recursive method: one node per call, labelled with its
 * arguments. A memoised call that returns from the memo check is a <b>hit</b> (its subtree is never
 * built); in plain recursion, a call with arguments already seen is a <b>repeat</b>: the work DP saves.
 */
final class RecursionTracker {

    private static final int MAX_NODES = 1500;

    private final List<TraceStep> steps;
    private final Map<RecursionSpec, Tree> trees = new LinkedHashMap<>();

    int newHits;
    int newRepeats;

    private static final class Node {
        int id = -1;
        Integer parent;
        int level;
        String label;
        int firstStep;
        int endStep = -1;
        boolean hasChildren;
        boolean repeat;
        String kind;
        int kindStep;
    }

    private static final class Tree {
        final RecursionSpec spec;
        final List<Node> nodes = new ArrayList<>();
        final Deque<Node> open = new ArrayDeque<>();
        final Set<String> seen = new HashSet<>();
        boolean truncated;

        Tree(RecursionSpec spec) {
            this.spec = spec;
        }
    }

    RecursionTracker(List<TraceStep> steps, List<RecursionSpec> specs) {
        this.steps = steps;
        for (RecursionSpec s : specs) {
            trees.put(s, new Tree(s));
        }
    }

    boolean active() {
        return !trees.isEmpty();
    }

    void track(int k) {
        newHits = 0;
        newRepeats = 0;
        TraceStep step = steps.get(k);
        for (Tree t : trees.values()) {
            int count = 0;
            for (FrameSnapshot f : step.stack()) {
                if (isFrame(f, t.spec)) {
                    count++;
                }
            }
            boolean newCall = "CALL".equals(step.event()) && !step.stack().isEmpty() && isFrame(step.stack().get(0), t.spec);
            int keep = newCall ? count - 1 : count;
            while (t.open.size() > Math.max(keep, 0)) {
                close(t, t.open.pop(), k - 1);
            }
            if (newCall) {
                open(t, step, k);
            }
        }
    }

    List<SearchTree> trees() {
        List<SearchTree> out = new ArrayList<>();
        int last = steps.size() - 1;
        for (Tree t : trees.values()) {
            if (t.nodes.isEmpty()) {
                continue;
            }
            List<SearchNode> nodes = new ArrayList<>();
            for (Node n : t.nodes) {
                String kind = n.kind != null ? n.kind : n.hasChildren ? "computed" : "base";
                int kindStep = n.kind != null ? n.kindStep : n.endStep < 0 ? last + 1 : n.endStep + 1;
                nodes.add(new SearchNode(n.id, n.parent, n.level, n.label, t.spec.method() + "(" + n.label + ")",
                        n.firstStep, n.endStep, kind, kindStep));
            }
            RecursionSpec s = t.spec;
            out.add(new SearchTree(s.method(), s.problem(), s.memo() == null ? "recursion" : "memo", null, null,
                    s.memo(), null, null, null, nodes, t.truncated));
        }
        return out;
    }

    private void open(Tree t, TraceStep step, int k) {
        Node parent = t.open.peek();
        Node n = new Node();
        n.parent = parent == null || parent.id < 0 ? null : parent.id;
        n.level = parent == null ? 0 : parent.level + 1;
        n.firstStep = k;
        n.label = arguments(step.stack().get(0));
        if (parent != null) {
            parent.hasChildren = true;
        }
        if (t.spec.memo() == null && !t.seen.add(n.label)) {
            n.repeat = true;
            n.kind = "repeat"; // the same call was made before: everything below it is done again
            n.kindStep = k;
            newRepeats++;
        }
        if (t.nodes.size() < MAX_NODES) {
            n.id = t.nodes.size();
            t.nodes.add(n);
        } else {
            t.truncated = true;
        }
        t.open.push(n);
    }

    private void close(Tree t, Node n, int endStep) {
        n.endStep = endStep;
        if (n.kind != null) {
            return;
        }
        RecursionSpec s = t.spec;
        if (s.memo() != null && !n.hasChildren && endStep >= 0 && endStep < steps.size()) {
            int line = steps.get(endStep).line();
            if (line >= s.checkStart() && line <= s.checkEnd()) {
                n.kind = "hit";
                n.kindStep = endStep + 1;
                newHits++;
            }
        }
    }

    /** "4" or "2, 5": the call's int/char/boolean/String parameters (arrays and objects left out). */
    private static String arguments(FrameSnapshot frame) {
        List<String> parts = new ArrayList<>();
        for (VariableSnapshot v : frame.variables()) {
            if (v.argument() && v.value().ref() == null && !v.name().equals("this")) {
                parts.add(v.value().display());
            }
        }
        return String.join(", ", parts);
    }

    private static boolean isFrame(FrameSnapshot f, RecursionSpec spec) {
        return f.methodName().equals(spec.method()) && spec.methodContains(f.line());
    }
}
