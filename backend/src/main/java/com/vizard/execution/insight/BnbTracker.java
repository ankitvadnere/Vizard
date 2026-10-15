package com.vizard.execution.insight;

import com.vizard.api.dto.analysis.BestUpdate;
import com.vizard.api.dto.analysis.SearchNode;
import com.vizard.api.dto.analysis.SearchTree;
import com.vizard.api.dto.trace.ConditionInsight;
import com.vizard.api.dto.trace.FrameSnapshot;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;
import com.vizard.execution.insight.CodeModel.BnbSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the branch-and-bound tree from the node objects of a live-node list:
 * <ul>
 *   <li>a node is <b>generated</b> when a new object of the node class appears; its parent is the
 *       node being expanded at that moment (the variable that holds the polled node)</li>
 *   <li>it is <b>live</b> while it is in the queue / stack / priority queue</li>
 *   <li>it is <b>expanded</b> when it is taken out and children are made from it</li>
 *   <li>it is <b>pruned</b> when a check drops it: its bound could not beat the best, so it is
 *       never added (or it is discarded when taken out)</li>
 * </ul>
 * Every change of the best-so-far variable is recorded with the node that caused it.
 */
final class BnbTracker {

    private static final int MAX_NODES = 1500;

    private final List<TraceStep> steps;
    private final Map<BnbSpec, Tree> trees = new LinkedHashMap<>();

    int newGenerated;
    int newExpanded;
    int newPruned;
    int newBest;

    private static final class Node {
        int id;
        long ref;
        Integer parent;
        int level;
        int firstStep;
        Integer liveStep;
        Integer pollStep;
        boolean expanded;
        int expandedStep;
        boolean pruned;
        int prunedStep;
        Map<String, String> fields = new LinkedHashMap<>();
    }

    private static final class Tree {
        final BnbSpec spec;
        final List<Node> nodes = new ArrayList<>();
        final Map<Long, Node> byRef = new LinkedHashMap<>();
        final List<BestUpdate> best = new ArrayList<>();
        final List<long[]> pendingPrunes = new ArrayList<>(); // {ref, step}
        Set<Long> lastLive = Set.of();
        String lastBest;
        Node lastCreated;
        boolean truncated;

        Tree(BnbSpec spec) {
            this.spec = spec;
        }
    }

    BnbTracker(List<TraceStep> steps, List<BnbSpec> specs) {
        this.steps = steps;
        for (BnbSpec s : specs) {
            trees.put(s, new Tree(s));
        }
    }

    boolean active() {
        return !trees.isEmpty();
    }

    void track(int k, ConditionInsight condition, int next) {
        newGenerated = 0;
        newExpanded = 0;
        newPruned = 0;
        newBest = 0;
        TraceStep step = steps.get(k);
        for (Tree t : trees.values()) {
            BnbSpec spec = t.spec;
            int frame = frameOf(step, spec);
            if (frame < 0) {
                continue;
            }
            EvalContext ctx = new EvalContext(step, frame);

            // Prunes decided by a check, shown once the check has answered.
            for (var it = t.pendingPrunes.iterator(); it.hasNext(); ) {
                long[] p = it.next();
                if (p[1] <= k) {
                    prune(t, p[0], k);
                    it.remove();
                }
            }

            // New node objects: children of the node being expanded.
            Long currentRef = refOf(ctx.lookup(spec.current()));
            for (HeapObjectSnapshot o : step.heap().values()) {
                if (!"object".equals(o.kind()) || !o.type().equals(spec.nodeClass()) || t.byRef.containsKey(o.id())) {
                    continue;
                }
                Node n = new Node();
                n.ref = o.id();
                n.firstStep = k;
                Node parent = currentRef == null ? null : t.byRef.get(currentRef);
                n.parent = parent == null ? null : parent.id;
                n.level = parent == null ? 0 : parent.level + 1;
                if (parent != null && !parent.expanded) {
                    parent.expanded = true;
                    parent.expandedStep = k;
                    newExpanded++;
                }
                t.byRef.put(o.id(), n);
                if (t.nodes.size() < MAX_NODES) {
                    n.id = t.nodes.size();
                    t.nodes.add(n);
                } else {
                    n.id = -1;
                    t.truncated = true;
                }
                t.lastCreated = n;
                newGenerated++;
            }
            // Field values (bound, profit, level...) as they are now.
            for (Node n : t.byRef.values()) {
                HeapObjectSnapshot o = step.heap().get(Long.toString(n.ref));
                if (o != null) {
                    for (VariableSnapshot f : o.fields()) {
                        if (f.value().ref() == null) {
                            n.fields.put(f.name(), f.value().display());
                        }
                    }
                }
            }

            // Live: in the list now. Taken out: was live, isn't any more.
            Set<Long> live = new HashSet<>();
            ValueSnapshot list = ctx.lookup(spec.frontier());
            HeapObjectSnapshot listObject = list == null || list.ref() == null ? null : step.heap().get(Long.toString(list.ref()));
            if (listObject != null) {
                for (ValueSnapshot e : listObject.elements()) {
                    if (e.ref() != null) {
                        live.add(e.ref());
                    }
                }
            }
            for (Long ref : live) {
                Node n = t.byRef.get(ref);
                if (n != null && n.liveStep == null) {
                    n.liveStep = k;
                }
            }
            if (listObject != null) {
                for (Long ref : t.lastLive) {
                    Node n = t.byRef.get(ref);
                    if (n != null && !live.contains(ref) && n.pollStep == null) {
                        n.pollStep = k;
                    }
                }
                t.lastLive = live;
            }

            // Checks: "if (u.bound <= best) continue;" true → u dropped; "if (v.bound > best) pq.add(v)" false → v dropped.
            if (condition != null && condition.result() != null && frame == 0) {
                int at = next > k ? next : k;
                if (spec.pruneLines().contains(condition.line()) && condition.result() && currentRef != null) {
                    t.pendingPrunes.add(new long[]{currentRef, at});
                }
                String guarded = spec.addGuards().get(condition.line());
                if (guarded != null && !condition.result()) {
                    Long ref = refOf(ctx.lookup(guarded));
                    if (ref != null) {
                        t.pendingPrunes.add(new long[]{ref, at});
                    }
                }
            }

            // The best so far.
            String best = valueOf(ctx.lookup(spec.bestVar()));
            if (best != null && t.lastBest != null && !best.equals(t.lastBest)) {
                Node by = t.lastCreated;
                t.best.add(new BestUpdate(k, best, by == null || by.id < 0 ? null : by.id));
                newBest++;
            }
            if (best != null) {
                t.lastBest = best;
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
            BnbSpec s = t.spec;
            List<SearchNode> nodes = new ArrayList<>();
            for (Node n : t.nodes) {
                String kind;
                int kindStep;
                if (n.pruned) {
                    kind = "pruned";
                    kindStep = n.prunedStep;
                } else if (n.expanded) {
                    kind = "expanded";
                    kindStep = n.expandedStep;
                } else if (n.liveStep != null && n.pollStep == null) {
                    kind = "live";
                    kindStep = last + 1;
                } else {
                    kind = "pruned"; // never added, or taken out and dropped without a recorded check
                    kindStep = n.pollStep != null ? n.pollStep : last + 1;
                }
                String bound = n.fields.getOrDefault(s.boundField(), "?");
                nodes.add(new SearchNode(n.id, n.parent, n.level, shortNumber(bound), describe(n, s), n.firstStep,
                        n.pollStep == null ? -1 : n.pollStep, kind, kindStep, n.liveStep, n.pollStep));
            }
            out.add(new SearchTree(s.method(), s.problem(), "bnb", s.levelField(), null, s.frontier(), s.order(),
                    null, null, nodes, t.truncated, s.bestVar(), List.copyOf(t.best), s.maximise()));
        }
        return out;
    }

    private void prune(Tree t, long ref, int k) {
        Node n = t.byRef.get(ref);
        if (n != null && !n.pruned && !n.expanded) {
            n.pruned = true;
            n.prunedStep = k;
            newPruned++;
        }
    }

    /** "level 1 · profit 40 · weight 4 · bound 76.0" */
    private static String describe(Node n, BnbSpec s) {
        List<String> parts = new ArrayList<>();
        n.fields.forEach((name, value) -> parts.add(name + " " + value));
        return String.join(" · ", parts);
    }

    /** 76.0 → 76, 69.33333333333333 → 69.3 */
    static String shortNumber(String display) {
        try {
            double d = Double.parseDouble(display);
            if (d == Math.rint(d) && Math.abs(d) < 1e12) {
                return Long.toString((long) d);
            }
            return String.format(java.util.Locale.ROOT, "%.1f", d);
        } catch (NumberFormatException e) {
            return display;
        }
    }

    private static int frameOf(TraceStep step, BnbSpec spec) {
        for (int f = 0; f < step.stack().size(); f++) {
            FrameSnapshot frame = step.stack().get(f);
            if (frame.methodName().equals(spec.method()) && spec.methodContains(frame.line())) {
                return f;
            }
        }
        return -1;
    }

    private static Long refOf(ValueSnapshot v) {
        return v == null ? null : v.ref();
    }

    private static String valueOf(ValueSnapshot v) {
        return v == null || v.ref() != null ? null : v.display();
    }
}
