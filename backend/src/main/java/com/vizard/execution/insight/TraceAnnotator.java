package com.vizard.execution.insight;

import com.vizard.api.dto.trace.ArrayAccessInsight;
import com.vizard.api.dto.trace.ArrayChangeInsight;
import com.vizard.api.dto.trace.ConditionInsight;
import com.vizard.api.dto.trace.ExecutionStats;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.LoopInsight;
import com.vizard.api.dto.trace.PointerInsight;
import com.vizard.api.dto.trace.RangeInsight;
import com.vizard.api.dto.trace.StepInsight;
import com.vizard.api.dto.trace.SwapInsight;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.execution.insight.CodeModel.ArrayAccessSite;
import com.vizard.execution.insight.CodeModel.ConditionSite;
import com.vizard.execution.insight.CodeModel.LoopSite;
import com.vizard.execution.insight.CodeModel.MethodSite;
import com.vizard.execution.insight.CodeModel.PointerSpec;
import com.vizard.execution.insight.CodeModel.RangeSpec;
import com.vizard.execution.insight.ExpressionEvaluator.HeapRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adds a {@link StepInsight} and running {@link ExecutionStats} to every step by combining the
 * recorded states with the {@link CodeModel}.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Condition results come from <b>what actually happened</b> (which line ran next) whenever
 *       the branch is on other lines; the evaluator is only a fallback (or the primary source for
 *       if/while, whose stop-point state is exactly the state the condition sees).</li>
 *   <li>Nothing here can fail a trace: any surprise produces a step without that insight.</li>
 * </ul>
 */
public final class TraceAnnotator {

    private static final int SWAP_WINDOW = 3;

    private TraceAnnotator() {
    }

    public static List<TraceStep> annotate(List<TraceStep> steps, CodeModel model) {
        int[] next = nextStepInSameCall(steps);
        Deque<FrameLoops> frames = new ArrayDeque<>();
        List<TraceStep> out = new ArrayList<>(steps.size());
        StatsCounter stats = new StatsCounter();

        for (int k = 0; k < steps.size(); k++) {
            TraceStep step = steps.get(k);
            if (step.stack().isEmpty()) {
                out.add(step.withAnnotations(null, stats.snapshot(k, step)));
                continue;
            }
            int[] iterationsStarted = {0};
            List<LoopInsight> loops = trackLoops(frames, step, model, iterationsStarted);

            EvalContext here = new EvalContext(step);
            ConditionSite condition = first(model.conditions().get(step.line()));
            EvalContext conditionState = here;
            if (condition != null && condition.kind().equals("for") && condition.branchOnOtherLines()) {
                // A for-header stop happens before init/update; the condition sees the state of the next stop.
                // (One-line loops are different: their extra stop sits right on the condition.)
                int n = next[k];
                if (n >= 0 && steps.get(n).line() != step.line() && !"EXCEPTION".equals(steps.get(n).event())) {
                    conditionState = new EvalContext(steps.get(n));
                }
            }

            ConditionInsight conditionInsight = null;
            // A RETURN step on an if line is "if (found) return mid;": the condition was evaluated too.
            boolean evaluatesCondition = condition != null && !"EXCEPTION".equals(step.event())
                    && (!"RETURN".equals(step.event()) || condition.kind().equals("if"));
            if (evaluatesCondition) {
                conditionInsight = describeCondition(condition, steps, k, next[k], conditionState);
                boolean nothingKnown = conditionInsight.result() == null
                        && conditionInsight.explanation().equals(conditionInsight.text());
                if (nothingKnown) {
                    conditionInsight = null; // e.g. the stop before "int i = 0" has run
                }
            }

            List<ArrayAccessInsight> accesses = accesses(model.accesses().get(step.line()), conditionState);
            StepInsight insight = new StepInsight(
                    conditionInsight,
                    accesses,
                    changes(k > 0 ? steps.get(k - 1) : null, step),
                    swap(steps, k),
                    pointers(model, step, here),
                    loops,
                    ranges(model, step, here),
                    loopBackTo(model, step));

            // One-line loops have no iteration counter; each true check of their condition starts one.
            if (conditionInsight != null && Boolean.TRUE.equals(conditionInsight.result())
                    && isOneLineLoop(model, condition)) {
                iterationsStarted[0]++;
            }
            stats.count(steps, k, insight, iterationsStarted[0]);
            out.add(step.withAnnotations(insight, stats.snapshot(k, step)));
        }
        return out;
    }

    /**
     * The JVM stops on the closing brace of a while/for body when it jumps back to the condition.
     * That stop shows as "line 15: }" in the editor, so say where it is going.
     */
    private static Integer loopBackTo(CodeModel model, TraceStep step) {
        if (!"LINE".equals(step.event())) {
            return null;
        }
        for (LoopSite loop : model.loops()) {
            boolean braceLine = loop.endLine() == step.line() && loop.endLine() > loop.bodyEnd();
            if (braceLine && !loop.kind().equals("do")) {
                return loop.conditionLine();
            }
        }
        return null;
    }

    private static boolean isOneLineLoop(CodeModel model, ConditionSite condition) {
        for (LoopSite loop : model.loops()) {
            if (loop.conditionLine() == condition.line() && loop.onOneLine()) {
                return true;
            }
        }
        return false;
    }

    // ---------- statistics --------------------------------------------------------------

    /** Running totals; each step gets a snapshot, so stepping backwards rewinds them. */
    private static final class StatsCounter {
        int comparisons;
        int swaps;
        int reads;
        int writes;
        int calls;
        int maxDepth;
        int iterations;

        void count(List<TraceStep> steps, int k, StepInsight insight, int iterationsStarted) {
            TraceStep step = steps.get(k);
            maxDepth = Math.max(maxDepth, step.depth());
            iterations += iterationsStarted;

            if (k > 0 && "CALL".equals(step.event())) {
                String method = step.stack().get(0).methodName();
                boolean entryPoint = method.equals("main") && step.depth() == 1;
                if (!entryPoint && !method.equals("<clinit>")) {
                    calls++;
                }
            }
            if (insight.swap() != null) {
                swaps++;
            }
            if (insight.condition() != null
                    && insight.accesses().stream().anyMatch(ArrayAccessInsight::compared)) {
                comparisons++;
            }
            if (linesFirstStep(steps, k)) {
                for (ArrayAccessInsight access : insight.accesses()) {
                    if (access.write()) {
                        writes++;
                    } else {
                        reads++;
                    }
                }
            }
        }

        /**
         * A line can produce two steps in one call: "about to run" and later "returning from"
         * (return n * f(n - 1)). Its array accesses happen once, so count them on the first.
         */
        private static boolean linesFirstStep(List<TraceStep> steps, int k) {
            TraceStep step = steps.get(k);
            if ("EXCEPTION".equals(step.event())) {
                return false;
            }
            if (!"RETURN".equals(step.event())) {
                return true;
            }
            for (int p = k - 1; p >= 0; p--) {
                TraceStep before = steps.get(p);
                if (before.depth() > step.depth()) {
                    continue; // inside a call made from this line
                }
                return before.depth() < step.depth() || before.line() != step.line()
                        || "RETURN".equals(before.event());
            }
            return true;
        }

        ExecutionStats snapshot(int k, TraceStep step) {
            return new ExecutionStats(k + 1, comparisons, swaps, reads, writes, calls,
                    Math.max(maxDepth, step.depth()), iterations);
        }
    }

    // ---------- ranges ------------------------------------------------------------------

    private static List<RangeInsight> ranges(CodeModel model, TraceStep step, EvalContext state) {
        MethodSite method = methodAt(model, step);
        if (method == null) {
            return List.of();
        }
        List<RangeInsight> result = new ArrayList<>();
        for (RangeSpec spec : method.ranges()) {
            if (!(ExpressionEvaluator.evaluate(spec.array(), state) instanceof HeapRef ref)) {
                continue;
            }
            HeapObjectSnapshot array = state.heapObject(ref.id());
            Object from = ExpressionEvaluator.fromSnapshot(state.lookup(spec.from()));
            Object to = ExpressionEvaluator.fromSnapshot(state.lookup(spec.to()));
            if (array != null && "array".equals(array.kind()) && from instanceof Long f && to instanceof Long t) {
                result.add(new RangeInsight(spec.array().text(), ref.id(), spec.from(), spec.to(),
                        f.intValue(), t.intValue()));
            }
        }
        return result;
    }

    // ---------- conditions --------------------------------------------------------------

    private static ConditionInsight describeCondition(ConditionSite site, List<TraceStep> steps, int k, int next,
                                                      EvalContext state) {
        Boolean fromFlow = null;
        if (next >= 0 && site.branchOnOtherLines()) {
            TraceStep after = steps.get(next);
            boolean threwHere = "EXCEPTION".equals(after.event()) && after.line() == site.line();
            if (!threwHere) {
                fromFlow = site.branchContains(after.line());
            }
        }
        Object evaluated = ExpressionEvaluator.evaluate(site.expression(), state);
        Boolean fromValues = evaluated instanceof Boolean b ? b : null;

        // if/while/do stop exactly where the condition is evaluated, so values are reliable there;
        // a for-header stop is before init/update, so trust control flow first.
        Boolean result = site.kind().equals("for")
                ? firstNonNull(fromFlow, fromValues)
                : firstNonNull(fromValues, fromFlow);

        String explanation = ExpressionEvaluator.explain(site.expression(), state);
        return new ConditionInsight(site.kind(), site.line(), site.expression().text(), explanation, result);
    }

    // ---------- arrays ------------------------------------------------------------------

    private static List<ArrayAccessInsight> accesses(List<ArrayAccessSite> sites, EvalContext state) {
        if (sites == null) {
            return List.of();
        }
        List<ArrayAccessInsight> result = new ArrayList<>();
        for (ArrayAccessSite site : sites) {
            Object array = ExpressionEvaluator.evaluate(site.array(), state);
            Object index = ExpressionEvaluator.evaluate(site.index(), state);
            if (array instanceof HeapRef ref && index instanceof Long i) {
                HeapObjectSnapshot object = state.heapObject(ref.id());
                if (object != null && "array".equals(object.kind()) && i >= 0 && i < object.length()) {
                    result.add(new ArrayAccessInsight(site.array().text(), ref.id(), i.intValue(), site.text(),
                            site.write(), site.inCondition()));
                }
            }
        }
        return result;
    }

    private static List<ArrayChangeInsight> changes(TraceStep previous, TraceStep current) {
        if (previous == null) {
            return List.of();
        }
        List<ArrayChangeInsight> result = new ArrayList<>();
        for (Map.Entry<String, HeapObjectSnapshot> entry : current.heap().entrySet()) {
            HeapObjectSnapshot now = entry.getValue();
            HeapObjectSnapshot before = previous.heap().get(entry.getKey());
            List<Integer> changed = changedCells(before, now);
            if (!changed.isEmpty()) {
                result.add(new ArrayChangeInsight(now.id(), changed));
            }
        }
        return result;
    }

    private static List<Integer> changedCells(HeapObjectSnapshot before, HeapObjectSnapshot now) {
        if (before == null || now == null || !"array".equals(now.kind()) || !"array".equals(before.kind())
                || before.elements().size() != now.elements().size()) {
            return List.of();
        }
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < now.elements().size(); i++) {
            if (!sameValue(before.elements().get(i), now.elements().get(i))) {
                changed.add(i);
            }
        }
        return changed;
    }

    /**
     * A swap usually takes three lines (temp = a[i]; a[i] = a[j]; a[j] = temp), so compare
     * the array now with how it looked up to three steps ago: exactly two cells exchanged = swap.
     */
    private static SwapInsight swap(List<TraceStep> steps, int k) {
        if (k == 0) {
            return null;
        }
        TraceStep current = steps.get(k);
        TraceStep previous = steps.get(k - 1);
        for (Map.Entry<String, HeapObjectSnapshot> entry : current.heap().entrySet()) {
            HeapObjectSnapshot now = entry.getValue();
            if (changedCells(previous.heap().get(entry.getKey()), now).isEmpty()) {
                continue; // the swap must complete at this step
            }
            for (int w = 1; w <= SWAP_WINDOW && k - w >= 0; w++) {
                HeapObjectSnapshot before = steps.get(k - w).heap().get(entry.getKey());
                List<Integer> diff = changedCells(before, now);
                if (diff.size() == 2) {
                    int a = diff.get(0);
                    int b = diff.get(1);
                    boolean exchanged = sameValue(before.elements().get(a), now.elements().get(b))
                            && sameValue(before.elements().get(b), now.elements().get(a));
                    if (exchanged && writtenLikeASwap(steps, k, w, entry.getKey(), a, b)) {
                        return new SwapInsight(now.id(), a, b);
                    }
                }
            }
        }
        return null;
    }

    /**
     * A swap writes its two cells from different lines ({@code a[i] = a[j]; a[j] = t;}), or both
     * at once from one line. Copying a temp array back (merge sort) can also leave two cells
     * exchanged, but writes them from the same line on different loop iterations: not a swap.
     */
    private static boolean writtenLikeASwap(List<TraceStep> steps, int k, int window, String key, int a, int b) {
        int changedA = lastChange(steps, k, window, key, a);
        int changedB = lastChange(steps, k, window, key, b);
        if (changedA < 0 || changedB < 0) {
            return false;
        }
        return changedA == changedB || steps.get(changedA - 1).line() != steps.get(changedB - 1).line();
    }

    /** The step (within the window) at which the cell got its current value; the line before it wrote it. */
    private static int lastChange(List<TraceStep> steps, int k, int window, String key, int cell) {
        for (int s = k; s > k - window && s > 0; s--) {
            HeapObjectSnapshot now = steps.get(s).heap().get(key);
            HeapObjectSnapshot before = steps.get(s - 1).heap().get(key);
            if (changedCells(before, now).contains(cell)) {
                return s;
            }
        }
        return -1;
    }

    private static List<PointerInsight> pointers(CodeModel model, TraceStep step, EvalContext state) {
        MethodSite method = methodAt(model, step);
        if (method == null) {
            return List.of();
        }
        List<PointerInsight> result = new ArrayList<>();
        for (PointerSpec spec : method.pointers()) {
            if (!(ExpressionEvaluator.evaluate(spec.array(), state) instanceof HeapRef ref)) {
                continue;
            }
            HeapObjectSnapshot array = state.heapObject(ref.id());
            if (array == null || !"array".equals(array.kind())) {
                continue;
            }
            for (String variable : spec.variables()) {
                ValueSnapshot value = state.lookup(variable);
                if (ExpressionEvaluator.fromSnapshot(value) instanceof Long index
                        && index >= -1 && index <= array.length()) {
                    result.add(new PointerInsight(spec.array().text(), ref.id(), variable, index.intValue()));
                }
            }
        }
        return result;
    }

    private static boolean sameValue(ValueSnapshot a, ValueSnapshot b) {
        return Objects.equals(a.kind(), b.kind()) && Objects.equals(a.display(), b.display())
                && Objects.equals(a.ref(), b.ref());
    }

    // ---------- loops -------------------------------------------------------------------

    /** Iteration counters for one method call (recursive calls each get their own). */
    private static final class FrameLoops {
        final Map<Integer, Integer> iterations = new HashMap<>();
        int previousLine = -1;
    }

    private static List<LoopInsight> trackLoops(Deque<FrameLoops> frames, TraceStep step, CodeModel model,
                                                int[] iterationsStarted) {
        int depth = step.depth();
        while (frames.size() > depth) {
            frames.pop();
        }
        if ("CALL".equals(step.event()) && frames.size() == depth) {
            frames.pop(); // a fresh call at this depth replaces the finished one
        }
        while (frames.size() < depth) {
            frames.push(new FrameLoops());
        }
        FrameLoops frame = frames.peek();
        int line = step.line();
        int previous = frame.previousLine;

        MethodSite method = methodAt(model, step);
        List<LoopInsight> active = new ArrayList<>();
        if (method == null) {
            frame.previousLine = line;
            return active;
        }
        for (LoopSite loop : model.loops()) {
            if (!method.contains(loop.headerLine())) {
                continue;
            }
            if (loop.onOneLine()) {
                continue; // no line change between iterations to count
            }
            int count = frame.iterations.getOrDefault(loop.headerLine(), 0);
            if (!loop.contains(line)) {
                count = 0; // left the loop (or not there yet)
            } else if (startsIteration(loop, previous, line)) {
                count++;
                iterationsStarted[0]++;
            }
            frame.iterations.put(loop.headerLine(), count);
            if (count > 0) {
                active.add(new LoopInsight(loop.kind(), loop.headerLine(), loop.variable(), count));
            }
        }
        frame.previousLine = line;
        return active;
    }

    private static boolean startsIteration(LoopSite loop, int previous, int line) {
        if (!loop.bodyContains(line) || loop.bodyContains(previous)) {
            return false;
        }
        if (loop.kind().equals("do")) {
            // first entry comes from before the loop; later ones from the while(...) line
            return previous == loop.conditionLine() || !loop.contains(previous);
        }
        return previous == loop.headerLine();
    }

    // ---------- helpers -----------------------------------------------------------------

    /**
     * For each step, the index of the next step in the same method call (skipping deeper calls),
     * or -1 if the call ends first.
     */
    static int[] nextStepInSameCall(List<TraceStep> steps) {
        int[] next = new int[steps.size()];
        List<Integer> lastSeen = new ArrayList<>(); // by depth, scanning backwards
        for (int k = steps.size() - 1; k >= 0; k--) {
            int depth = steps.get(k).depth();
            while (lastSeen.size() > depth + 1) {
                lastSeen.remove(lastSeen.size() - 1); // a shallower step blocks deeper ones
            }
            while (lastSeen.size() <= depth) {
                lastSeen.add(-1);
            }
            next[k] = lastSeen.get(depth);
            boolean startsNewCall = "CALL".equals(steps.get(k).event());
            lastSeen.set(depth, startsNewCall ? -1 : k); // a CALL step is not a continuation of the previous call
        }
        return next;
    }

    /** The innermost method or constructor whose source contains the current line. */
    private static MethodSite methodAt(CodeModel model, TraceStep step) {
        MethodSite best = null;
        for (MethodSite m : model.methods()) {
            if (m.contains(step.line()) && (best == null || m.startLine() >= best.startLine())) {
                best = m;
            }
        }
        return best;
    }

    private static <T> T first(List<T> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static Boolean firstNonNull(Boolean a, Boolean b) {
        return a != null ? a : b;
    }
}
