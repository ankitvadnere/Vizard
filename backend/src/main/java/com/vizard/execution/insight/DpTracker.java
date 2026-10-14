package com.vizard.execution.insight;

import com.vizard.api.dto.analysis.DpTable;
import com.vizard.api.dto.analysis.DpWrite;
import com.vizard.api.dto.trace.ConditionInsight;
import com.vizard.api.dto.trace.DpInsight;
import com.vizard.api.dto.trace.FrameSnapshot;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.TraceStep;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.execution.insight.CodeModel.TableSite;
import com.vizard.execution.insight.CodeModel.TableSpec;
import com.vizard.execution.insight.ExpressionEvaluator.HeapRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows the dynamic-programming tables through the trace. On a line that uses a table it reports
 * the cell being computed, the cells read and the expression with their values; every write is
 * recorded with the step at which the new value can be seen, which gives the order the table was filled in.
 */
final class DpTracker {

    private static final int MAX_WRITES = 5000;

    private final List<TraceStep> steps;
    private final Map<TableSpec, State> tables = new LinkedHashMap<>();

    /** Distinct cells that got their first value at the last {@link #insight} call. */
    int newCells;

    private static final class State {
        final List<DpWrite> writes = new ArrayList<>();
        final List<int[]> pending = new ArrayList<>(); // {row, col, step}
        final Set<String> filled = new HashSet<>();
        List<String> rowLabels;
        List<String> colLabels;
        int rows;
        int cols;
    }

    DpTracker(List<TraceStep> steps, List<TableSpec> specs) {
        this.steps = steps;
        for (TableSpec s : specs) {
            tables.put(s, new State());
        }
    }

    boolean active() {
        return !tables.isEmpty();
    }

    /**
     * @param condition the condition evaluated on this line (for a check's result), or null
     * @param next      the next step in the same call, where a write's value shows, or -1
     */
    DpInsight insight(int k, ConditionInsight condition, int next) {
        newCells = 0;
        TraceStep step = steps.get(k);
        DpInsight result = null;
        for (Map.Entry<TableSpec, State> e : tables.entrySet()) {
            TableSpec spec = e.getKey();
            State state = e.getValue();
            for (var it = state.pending.iterator(); it.hasNext(); ) {
                int[] w = it.next();
                if (w[2] <= k) {
                    if (state.filled.add(w[0] + "," + w[1])) {
                        newCells++;
                    }
                    it.remove();
                }
            }
            if (step.stack().isEmpty()) {
                continue;
            }
            FrameSnapshot top = step.stack().get(0);
            if (!top.methodName().equals(spec.method()) || !spec.methodContains(step.line())
                    || !"LINE".equals(step.event()) && !"CALL".equals(step.event())) {
                continue;
            }
            TableSite site = spec.sites().stream().filter(s -> s.line() == step.line()).findFirst().orElse(null);
            if (site == null) {
                continue;
            }
            EvalContext ctx = new EvalContext(step);
            Long ref = ExpressionEvaluator.evaluate(new Expr.Name(spec.table()), ctx) instanceof HeapRef r ? r.id() : null;
            int[] cell = cellOf(site.cell(), spec.dims(), ctx);
            if (ref == null || cell == null) {
                continue;
            }
            if (state.rowLabels == null) {
                state.rowLabels = labels(spec.rowString(), spec.rowOffset(), ctx);
                state.colLabels = labels(spec.colString(), spec.colOffset(), ctx);
            }
            HeapObjectSnapshot array = step.heap().get(Long.toString(ref));
            if (array != null) {
                int rows = spec.dims() == 2 ? array.length() : 1;
                int cols = spec.dims() == 2 && !array.elements().isEmpty() && array.elements().get(0).ref() != null
                        && step.heap().get(Long.toString(array.elements().get(0).ref())) != null
                        ? step.heap().get(Long.toString(array.elements().get(0).ref())).length()
                        : spec.dims() == 1 ? array.length() : 0;
                state.rows = Math.max(state.rows, rows);
                state.cols = Math.max(state.cols, cols);
            }
            List<List<Integer>> reads = new ArrayList<>();
            for (Expr read : site.reads()) {
                int[] c = cellOf(read, spec.dims(), ctx);
                if (c != null && c[0] >= 0 && c[1] >= 0) {
                    reads.add(List.of(c[0], c[1]));
                }
            }
            int after = next > k ? next : Math.min(k + 1, steps.size() - 1);
            String value = null;
            if (site.write()) {
                if (state.writes.size() < MAX_WRITES) {
                    state.writes.add(new DpWrite(cell[0], cell[1], after));
                }
                state.pending.add(new int[]{cell[0], cell[1], after});
                value = cellValue(steps.get(after), ref, cell, spec.dims());
            } else if (site.label().equals("check")) {
                value = condition == null || condition.result() == null ? null : condition.result().toString();
            } else if (after != k) {
                Object v = ExpressionEvaluator.evaluate(new Expr.Name(site.label()), new EvalContext(steps.get(after)));
                value = v == null ? null : show(ExpressionEvaluator.display(v, ctx));
            }
            if (result == null) {
                result = new DpInsight(spec.table(), ref, List.of(cell[0], cell[1]), reads, site.label(), site.shown(),
                        substitute(site, ctx), value, site.write());
            }
        }
        return result;
    }

    List<DpTable> tables() {
        List<DpTable> out = new ArrayList<>();
        for (Map.Entry<TableSpec, State> e : tables.entrySet()) {
            TableSpec s = e.getKey();
            State st = e.getValue();
            if (st.writes.isEmpty()) {
                continue;
            }
            out.add(new DpTable(s.method(), s.table(), s.kind(), s.problem(), s.dims(), st.rows, st.cols, s.rowVar(),
                    s.colVar(), st.rowLabels, st.colLabels, List.copyOf(st.writes)));
        }
        return out;
    }

    // ---------- helpers ------------------------------------------------------------------

    /** {row, col} of dp[i][j] (or {0, i} for dp[i]) in this state. */
    private static int[] cellOf(Expr access, int dims, EvalContext ctx) {
        if (!(access instanceof Expr.ArrayAccess a)) {
            return null;
        }
        Object col = ExpressionEvaluator.evaluate(a.index(), ctx);
        if (!(col instanceof Long c)) {
            return null;
        }
        if (dims == 1) {
            return new int[]{0, c.intValue()};
        }
        if (a.array() instanceof Expr.ArrayAccess rowAccess
                && ExpressionEvaluator.evaluate(rowAccess.index(), ctx) instanceof Long r) {
            return new int[]{r.intValue(), c.intValue()};
        }
        return null;
    }

    private static String cellValue(TraceStep step, long ref, int[] cell, int dims) {
        HeapObjectSnapshot table = step.heap().get(Long.toString(ref));
        if (table == null) {
            return null;
        }
        HeapObjectSnapshot row = table;
        if (dims == 2) {
            if (cell[0] < 0 || cell[0] >= table.elements().size() || table.elements().get(cell[0]).ref() == null) {
                return null;
            }
            row = step.heap().get(Long.toString(table.elements().get(cell[0]).ref()));
        }
        if (row == null || cell[1] < 0 || cell[1] >= row.elements().size()) {
            return null;
        }
        ValueSnapshot v = row.elements().get(cell[1]);
        return show(v.display());
    }

    /** The expression with each table cell and array element replaced by its value; Math.max → max. */
    private static String substitute(TableSite site, EvalContext ctx) {
        List<Expr> parts = new ArrayList<>(site.reads());
        parts.addAll(site.values());
        parts.sort(Comparator.comparingInt((Expr p) -> p.text().length()).reversed());
        String text = site.shown();
        for (Expr p : parts) {
            Object v = ExpressionEvaluator.evaluate(p, ctx);
            if (v != null) {
                text = text.replace(p.text(), show(ExpressionEvaluator.display(v, ctx)));
            }
        }
        for (String name : site.names()) {
            Object v = ExpressionEvaluator.evaluate(new Expr.Name(name), ctx);
            if (v instanceof Long || v instanceof Double || v instanceof Boolean || v instanceof Character) {
                text = text.replaceAll("(?<![\\w.])" + java.util.regex.Pattern.quote(name) + "(?![\\w(])",
                        java.util.regex.Matcher.quoteReplacement(show(ExpressionEvaluator.display(v, ctx))));
            }
        }
        return text.replace("Math.max", "max").replace("Math.min", "min").replace("Integer.MAX_VALUE", "∞");
    }

    private static String show(String display) {
        if (display == null) {
            return null;
        }
        return display.equals(Integer.toString(Integer.MAX_VALUE)) || display.equals(Long.toString(Long.MAX_VALUE))
                ? "∞" : display;
    }

    /** "" for the empty prefix, then the letters of the string (LCS rows "", "A", "B", ...). */
    private static List<String> labels(String variable, int offset, EvalContext ctx) {
        if (variable == null) {
            return null;
        }
        if (!(ExpressionEvaluator.evaluate(new Expr.Name(variable), ctx) instanceof String s)) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < offset; i++) {
            labels.add("");
        }
        for (char c : s.toCharArray()) {
            labels.add(String.valueOf(c));
        }
        return labels;
    }
}
