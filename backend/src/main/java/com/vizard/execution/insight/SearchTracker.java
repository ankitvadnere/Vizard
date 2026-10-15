package com.vizard.execution.insight;

import com.vizard.api.dto.analysis.BestUpdate;
import com.vizard.api.dto.analysis.SearchNode;
import com.vizard.api.dto.analysis.SearchTree;
import com.vizard.api.dto.trace.ConditionInsight;
import com.vizard.api.dto.trace.FrameSnapshot;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.StepInsight;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;
import com.vizard.execution.insight.CodeModel.SearchSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the state-space tree of each backtracking method from the recorded calls, one step at a time.
 *
 * <ul>
 *   <li>A <b>node</b> starts when the method is called and ends when that call returns; its parent is
 *       the call it was made from. Its label is the choice that led to it: the loop variable in the
 *       caller ({@code col = 2}), or take / leave the element ({@code +3} / {@code −3}).</li>
 *   <li>A <b>rejected</b> choice: the check around the recursive call was evaluated false (or a
 *       {@code continue} check true), so that branch was never entered.</li>
 *   <li>A <b>pruned</b> call: a bound check such as {@code if (sum > target) return;} was true.</li>
 *   <li>A <b>solution</b>: while this call was the deepest one running, the program recorded something:
 *       printed output, increased a counter, added to a result collection; or the call was a leaf that
 *       returned true. Changes to the search's own state (the board, the current path) don't count.</li>
 * </ul>
 */
final class SearchTracker {

    private static final int MAX_NODES = 1500;

    private final List<TraceStep> steps;
    private final Map<SearchSpec, Tree> trees = new LinkedHashMap<>();

    // Counter increments of the last track() call.
    int newNodes;
    int newPruned;
    int newSolutions;
    int newBacktracks;
    int newBestUpdates;

    private static final class Node {
        int id = -1;
        Integer parent;
        Node parentNode;
        int level;
        String label;
        String choice;
        int firstStep;
        int endStep = -1;
        boolean rejected;
        boolean hasChildren;
        boolean cut;
        int cutStep;
        boolean solution;
        int solutionStep;
    }

    /** A choice the check rejected, shown once control moves on (when the check's result is visible). */
    private record Rejection(int atStep, Node parent, String[] label) {
    }

    private static final class Tree {
        final SearchSpec spec;
        final List<Rejection> pending = new ArrayList<>();
        final List<BestUpdate> bestUpdates = new ArrayList<>();
        final List<Node> nodes = new ArrayList<>();
        final Deque<Node> open = new ArrayDeque<>();
        Node lastDeepest;
        boolean truncated;
        Long depthBound;
        Long branching;
        boolean measured;

        Tree(SearchSpec spec) {
            this.spec = spec;
        }
    }

    SearchTracker(List<TraceStep> steps, List<SearchSpec> specs) {
        this.steps = steps;
        for (SearchSpec s : specs) {
            trees.put(s, new Tree(s));
        }
    }

    boolean active() {
        return !trees.isEmpty();
    }

    /**
     * @param next the next step in the same call (where a check's outcome shows), or -1
     */
    void track(int k, StepInsight insight, int next) {
        newNodes = 0;
        newPruned = 0;
        newSolutions = 0;
        newBacktracks = 0;
        newBestUpdates = 0;
        TraceStep step = steps.get(k);
        for (Tree t : trees.values()) {
            SearchSpec spec = t.spec;
            if (k > 0 && spec.bestVar() != null) {
                String before = staticValue(steps.get(k - 1), spec.bestVar());
                String now = staticValue(step, spec.bestVar());
                if (now != null && before != null && !now.equals(before)) {
                    // Branch and bound: the best so far improved, found by the deepest call running.
                    t.bestUpdates.add(new BestUpdate(k, now, t.lastDeepest == null || t.lastDeepest.id < 0
                            ? null : t.lastDeepest.id));
                    newBestUpdates++;
                    if (t.lastDeepest != null) {
                        markSolution(t.lastDeepest, k);
                    }
                }
            }
            if (k > 0 && t.lastDeepest != null && recordedSomething(steps.get(k - 1), step, spec)) {
                markSolution(t.lastDeepest, k);
            }

            int count = 0;
            for (FrameSnapshot f : step.stack()) {
                if (isSearchFrame(f, spec)) {
                    count++;
                }
            }
            boolean topIsSearch = !step.stack().isEmpty() && isSearchFrame(step.stack().get(0), spec);
            boolean newCall = "CALL".equals(step.event()) && topIsSearch;
            int keep = newCall ? count - 1 : count;
            while (t.open.size() > Math.max(keep, 0)) {
                close(t, t.open.pop(), k - 1);
            }
            if (newCall) {
                open(t, step, k);
            }

            ConditionInsight c = insight == null ? null : insight.condition();
            Node here = t.open.peek();
            if (topIsSearch && c != null && c.result() != null && here != null) {
                boolean result = c.result();
                if (spec.cutLines().contains(c.line()) && result && !here.cut) {
                    here.cut = true;
                    here.cutStep = k;
                    newPruned++;
                }
                boolean rejected = spec.rejectIfFalse().contains(c.line()) && !result
                        || spec.rejectIfTrue().contains(c.line()) && result;
                if (rejected) {
                    int callLine = spec.callLines().stream().filter(l -> l >= c.line()).findFirst().orElse(c.line());
                    t.pending.add(new Rejection(next > k ? next : k, here, choiceLabel(spec, step, 0, callLine)));
                }
            }
            for (var it = t.pending.iterator(); it.hasNext(); ) {
                Rejection r = it.next();
                if (r.atStep() <= k) {
                    reject(t, k, r.parent(), r.label());
                    it.remove();
                }
            }
            t.lastDeepest = t.open.peek();
        }
    }

    List<SearchTree> trees() {
        List<SearchTree> result = new ArrayList<>();
        int last = steps.size() - 1;
        for (Tree t : trees.values()) {
            if (t.nodes.isEmpty()) {
                continue;
            }
            List<SearchNode> nodes = new ArrayList<>(t.nodes.size());
            for (Node n : t.nodes) {
                String kind;
                int kindStep;
                if (n.rejected) {
                    kind = "rejected";
                    kindStep = n.firstStep;
                } else if (n.cut) {
                    kind = "pruned";
                    kindStep = n.cutStep;
                } else if (n.solution) {
                    kind = "solution";
                    kindStep = n.solutionStep;
                } else {
                    kind = n.hasChildren ? "explored" : "dead-end";
                    kindStep = n.endStep < 0 ? last + 1 : n.endStep + 1;
                }
                nodes.add(new SearchNode(n.id, n.parent, n.level, n.label, n.choice, n.firstStep, n.endStep, kind,
                        kindStep));
            }
            SearchSpec s = t.spec;
            result.add(new SearchTree(s.method(), s.problem(), s.kind(), s.depthParam(), s.choiceVar(),
                    s.assignment(), s.assignmentShape(), t.depthBound, t.branching, nodes, t.truncated,
                    s.bestVar(), List.copyOf(t.bestUpdates), null));
        }
        return result;
    }

    // ---------- nodes ---------------------------------------------------------------------

    private void open(Tree t, TraceStep step, int k) {
        Node parent = t.open.peek();
        Node n = new Node();
        n.parentNode = parent;
        n.parent = parent == null || parent.id < 0 ? null : parent.id;
        n.level = parent == null ? 0 : parent.level + 1;
        n.firstStep = k;
        if (parent == null) {
            n.label = "";
            n.choice = "first call";
            measure(t, step);
        } else {
            parent.hasChildren = true;
            String[] label = step.stack().size() > 1 ? choiceLabel(t.spec, step, 1, step.stack().get(1).line())
                    : new String[]{"?", "?"};
            n.label = label[0];
            n.choice = label[1];
        }
        add(t, n);
        t.open.push(n);
        newNodes++;
    }

    private void reject(Tree t, int k, Node here, String[] label) {
        Node n = new Node();
        n.parentNode = here;
        n.parent = here.id < 0 ? null : here.id;
        n.level = here.level + 1;
        n.label = label[0];
        n.choice = label[1];
        n.firstStep = k;
        n.endStep = k;
        n.rejected = true;
        add(t, n);
        newPruned++;
    }

    private void add(Tree t, Node n) {
        if (t.nodes.size() >= MAX_NODES) {
            t.truncated = true;
            return;
        }
        n.id = t.nodes.size();
        t.nodes.add(n);
    }

    private void close(Tree t, Node n, int endStep) {
        n.endStep = endStep;
        if (!n.hasChildren && !n.solution && !n.cut && returnedTrue(endStep)) {
            markSolution(n, endStep + 1);
        }
        if (n.parentNode != null) {
            newBacktracks++;
        }
    }

    private void markSolution(Node n, int k) {
        if (!n.solution && !n.cut) {
            n.solution = true;
            n.solutionStep = k;
            newSolutions++;
        }
    }

    /**
     * {label, choice in words} for the branch a call at {@code callLine} takes, read in the frame
     * that makes the choice.
     */
    private static String[] choiceLabel(SearchSpec spec, TraceStep step, int frame, int callLine) {
        EvalContext ctx = new EvalContext(step, frame);
        if (spec.kind().equals("loop")) {
            String v = display(ExpressionEvaluator.evaluate(new Expr.Name(spec.choiceVar()), ctx), ctx);
            if (spec.item() != null) {
                String x = display(ExpressionEvaluator.evaluate(spec.item(), ctx), ctx);
                return new String[]{x, spec.choiceVar() + " = " + v + " (" + spec.item().text() + " = " + x + ")"};
            }
            return new String[]{v, spec.choiceVar() + " = " + v};
        }
        boolean take = callLine == spec.includeLine();
        if (spec.item() == null) {
            return new String[]{take ? "take" : "leave", take ? "take" : "leave"};
        }
        String v = display(ExpressionEvaluator.evaluate(spec.item(), ctx), ctx);
        return take
                ? new String[]{"+" + v, "take " + spec.item().text() + " = " + v}
                : new String[]{"−" + v, "leave " + spec.item().text() + " = " + v};
    }

    private static String display(Object value, EvalContext ctx) {
        return value == null ? "?" : ExpressionEvaluator.display(value, ctx);
    }

    /** n (the depth of the full tree) and the number of choices per level, from the first call. */
    private static void measure(Tree t, TraceStep step) {
        if (t.measured) {
            return;
        }
        t.measured = true;
        EvalContext ctx = new EvalContext(step, 0);
        SearchSpec s = t.spec;
        if (s.goalBound() != null && ExpressionEvaluator.evaluate(s.goalBound(), ctx) instanceof Long d) {
            // levels still to go: from the first call's depth (row = 0, count = 1) to the goal
            Object start = s.depthParam() == null ? null : ExpressionEvaluator.evaluate(new Expr.Name(s.depthParam()), ctx);
            t.depthBound = start instanceof Long st && st > 0 && st < d ? d - st : d;
        }
        if (s.kind().equals("binary")) {
            t.branching = 2L;
        } else if (s.choiceFrom() != null && s.choiceTo() != null
                && ExpressionEvaluator.evaluate(s.choiceFrom(), ctx) instanceof Long from
                && ExpressionEvaluator.evaluate(s.choiceTo(), ctx) instanceof Long to) {
            t.branching = Math.max(0, to - from + (s.inclusive() ? 1 : 0));
            if (t.depthBound == null && s.problem().equals("subsets")) {
                t.depthBound = t.branching; // combinations: one level per element
            }
        }
    }

    // ---------- what happened -------------------------------------------------------------

    private static boolean isSearchFrame(FrameSnapshot f, SearchSpec spec) {
        return f.methodName().equals(spec.method()) && spec.methodContains(f.line());
    }

    private boolean returnedTrue(int endStep) {
        if (endStep < 0 || endStep >= steps.size()) {
            return false;
        }
        TraceStep s = steps.get(endStep);
        return "RETURN".equals(s.event()) && s.returnValue() != null && Boolean.TRUE.equals(s.returnValue().value());
    }

    /**
     * Between two steps the program printed something, increased a static counter, or added to a
     * collection that is not part of the search's own state.
     */
    /** A static field's value by its simple name (best → Main.best), as displayed; null if there is none. */
    static String staticValue(TraceStep step, String name) {
        for (VariableSnapshot v : step.statics()) {
            if (v.name().endsWith("." + name)) {
                return v.value().display();
            }
        }
        return null;
    }

    private static boolean recordedSomething(TraceStep before, TraceStep after, SearchSpec spec) {
        if (after.outputLength() > before.outputLength()) {
            return true;
        }
        Set<String> state = new HashSet<>(spec.stateNames());
        if (spec.bestVar() != null) {
            state.add(spec.bestVar()); // handled as a best update
        }
        for (VariableSnapshot now : after.statics()) {
            String plain = now.name().substring(now.name().indexOf('.') + 1);
            if (state.contains(plain) || !(now.value().value() instanceof Number n)) {
                continue;
            }
            for (VariableSnapshot old : before.statics()) {
                // count++ or a new best (best = Math.min(best, cost) goes down)
                if (old.name().equals(now.name()) && old.value().value() instanceof Number o
                        && n.doubleValue() != o.doubleValue()) {
                    return true;
                }
            }
        }
        Set<Long> stateRefs = new HashSet<>();
        for (TraceStep s : List.of(before, after)) {
            for (FrameSnapshot f : s.stack()) {
                for (VariableSnapshot v : f.variables()) {
                    addIfState(stateRefs, state, v);
                }
            }
            for (VariableSnapshot v : s.statics()) {
                addIfState(stateRefs, state, new VariableSnapshot(v.name().substring(v.name().indexOf('.') + 1),
                        v.type(), v.value(), false));
            }
        }
        for (HeapObjectSnapshot now : after.heap().values()) {
            if (!"collection".equals(now.kind()) || stateRefs.contains(now.id())) {
                continue;
            }
            HeapObjectSnapshot old = before.heap().get(Long.toString(now.id()));
            if (old != null && now.length() > old.length()) {
                return true;
            }
        }
        return false;
    }

    private static void addIfState(Set<Long> refs, Set<String> state, VariableSnapshot v) {
        ValueSnapshot value = v.value();
        if (state.contains(v.name()) && value.ref() != null) {
            refs.add(value.ref());
        }
    }
}
