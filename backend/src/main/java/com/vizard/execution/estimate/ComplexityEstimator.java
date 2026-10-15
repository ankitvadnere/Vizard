package com.vizard.execution.estimate;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.vizard.execution.estimate.Cost.Factor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A static estimate of the running time of methods Vizard does not recognise. It reads the
 * structure of the code, never runs it:
 * <ul>
 *   <li>a loop runs as many times as its bound allows: {@code i < n} with {@code i++} is n times,
 *       {@code i *= 2} or {@code n /= 2} is log n times, {@code i * i <= n} is √n times, a literal
 *       bound is a constant;</li>
 *   <li>nested loops multiply, statements one after another add, an if counts its larger branch
 *       (the worst case);</li>
 *   <li>a call to another of your methods costs what that method costs, with its parameters
 *       replaced by the arguments; a few library calls have known costs (Arrays.sort: n log n);</li>
 *   <li>a recursive method gives a recurrence: T(n) = a·T(n/b) + f(n) is solved by the master
 *       theorem, T(n) = a·T(n − c) + f(n) by unrolling it.</li>
 * </ul>
 * The result is an upper bound for the worst case, not a proof: a loop that exits early, or data
 * that makes a branch impossible, can make the real cost smaller.
 */
public final class ComplexityEstimator {

    /**
     * @param time       e.g. "O(n log n)", or null when no estimate could be made
     * @param space      extra space: recursion depth and arrays the method allocates, or null
     * @param recurrence "T(n) = 2T(n/2) + O(n)" for a recursive method, else null
     * @param rule       how the recurrence was solved ("Master theorem, case 2"), else null
     * @param steps      the reasoning, one line per loop, call or recurrence
     * @param legend     what each letter stands for ("n = arr.length")
     */
    public record Estimate(String method, int line, String time, String space, String recurrence, String rule,
                           List<String> steps, List<String> legend) {
    }

    private final CompilationUnit cu;
    private final Map<MethodDeclaration, Result> results = new HashMap<>();
    private final Set<MethodDeclaration> inProgress = new LinkedHashSet<>();
    private final Map<String, String> fieldTypes = new HashMap<>();
    private final Map<String, Cost> fieldLengths = new HashMap<>();

    /** One method's result, in its own symbols (its parameters appear as var:p / len:p). */
    private static final class Result {
        Cost time = Cost.ONE;
        Cost space = Cost.ONE;
        String timeText; // overrides the rendering (n^log₂3)
        String recurrence;
        List<Cost> recurrenceArgs = List.of();
        String rule;
        boolean recursive;
        boolean failed;
        final List<Step> steps = new ArrayList<>();
        /** int n = arr.length: call that size n. */
        final Map<String, String> preferred = new LinkedHashMap<>();
    }

    /** A line of reasoning with costs rendered later, once letters are chosen: "{0} iterations". */
    private record Step(int depth, String text, List<Cost> costs) {
    }

    private ComplexityEstimator(CompilationUnit cu) {
        this.cu = cu;
        for (FieldDeclaration f : cu.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator v : f.getVariables()) {
                fieldTypes.put(v.getNameAsString(), v.getTypeAsString());
                v.getInitializer().ifPresent(init -> {
                    if (init.isArrayInitializerExpr()) {
                        // a literal array is the input: its length is a size, named after the array
                        return;
                    }
                    if (init instanceof ArrayCreationExpr ac && !ac.getLevels().isEmpty()
                            && ac.getLevels().get(0).getDimension().isPresent()) {
                        Scope s = new Scope(null);
                        fieldLengths.put(v.getNameAsString(), sizeOf(ac.getLevels().get(0).getDimension().get(), s));
                    }
                });
            }
        }
    }

    /**
     * Estimates every method except those in {@code skip} (recognised algorithms keep their catalogue
     * entry) and except constant-time helpers. main is estimated only when it is the only method
     * with loops or recursion: then the program's work is in main.
     */
    public static List<Estimate> estimate(CompilationUnit cu, Set<String> skip) {
        ComplexityEstimator e = new ComplexityEstimator(cu);
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getBody().isPresent()).toList();
        List<Estimate> out = new ArrayList<>();
        boolean otherWork = false;
        for (MethodDeclaration m : methods) {
            if (m.getNameAsString().equals("main")) {
                continue;
            }
            Result r = e.resultOf(m);
            if (skip.contains(m.getNameAsString())) {
                otherWork = true;
                continue;
            }
            if (r.recursive || !r.time.isConstant() || r.failed) {
                otherWork = true;
                out.add(e.render(m, r));
            }
        }
        if (!otherWork) {
            for (MethodDeclaration m : methods) {
                if (m.getNameAsString().equals("main") && !skip.contains("main")) {
                    Result r = e.resultOf(m);
                    if (!r.time.isConstant() || r.failed) {
                        out.add(e.render(m, r));
                    }
                }
            }
        }
        return out;
    }

    // ---------- one method ------------------------------------------------------------------

    private Result resultOf(MethodDeclaration m) {
        Result done = results.get(m);
        if (done != null) {
            return done;
        }
        inProgress.add(m);
        Result r = new Result();
        try {
            analyse(m, r);
        } catch (RuntimeException ex) {
            r.failed = true;
            r.steps.add(new Step(0, "Vizard could not follow this method's structure.", List.of()));
        }
        inProgress.remove(m);
        results.put(m, r);
        return r;
    }

    private void analyse(MethodDeclaration m, Result r) {
        Scope scope = new Scope(m);
        for (Parameter p : m.getParameters()) {
            scope.types.put(p.getNameAsString(), p.getTypeAsString());
        }
        scope.range = rangeKey(m, scope);
        if (scope.range != null) {
            String[] lohi = scope.range.substring("range:".length()).split(":");
            scope.positions.add(lohi[0]);
            scope.positions.add(lohi[1]);
            // other int parameters of a range method are positions too (mid in merge(a, lo, mid, hi))
            for (Parameter p : m.getParameters()) {
                String t = p.getTypeAsString();
                if ((t.equals("int") || t.equals("long"))
                        && !p.getNameAsString().matches("n|N|size|len|length|count|total")) {
                    scope.positions.add(p.getNameAsString());
                }
            }
        }
        Walker w = new Walker(m, scope, r);
        Cost body = w.stmt(m.getBody().get(), 0);
        Cost space = w.allocated;
        if (w.selfCalls.isEmpty()) {
            r.time = body;
            r.space = space;
            return;
        }
        r.recursive = true;
        solveRecurrence(m, w, body, space, r);
    }

    // ---------- recurrences -----------------------------------------------------------------

    /** How one argument of a recursive call relates to the parameter: n − 1, n / 2, mid, i + 1 ... */
    private record Shrink(String kind, int amount) {
    }

    private void solveRecurrence(MethodDeclaration m, Walker w, Cost work, Cost space, Result r) {
        // How many self-calls one invocation makes, and how each argument shrinks.
        int calls = callCount(m.getBody().get());
        Cost branching = loopFactorOfCalls(m);
        String key = null;
        String mode = null; // "sub" or "div"
        Set<Integer> amounts = new java.util.TreeSet<>();
        for (MethodCallExpr call : w.selfCalls) {
            Shrink s = null;
            String k = null;
            for (int i = 0; i < call.getArguments().size() && i < m.getParameters().size(); i++) {
                String p = m.getParameter(i).getNameAsString();
                Shrink a = shrinkOf(call.getArgument(i), p, m, w.scope);
                if (a == null) {
                    continue;
                }
                if (a.kind().equals("mid")) {
                    String range = w.scope.range;
                    if (range != null) {
                        s = new Shrink("div", 2);
                        k = range;
                    }
                    continue;
                }
                if (a.kind().equals("add")) {
                    Expression limit = baseLimit(m, p);
                    if (limit == null) {
                        continue;
                    }
                    Cost size = sizeOf(limit, w.scope);
                    String single = size.singleSymbol();
                    if (single == null) {
                        continue;
                    }
                    s = new Shrink("sub", a.amount());
                    k = single;
                    continue;
                }
                s = a;
                k = a.kind().equals("sub") || a.kind().equals("div") ? "var:" + p : null;
            }
            if (s == null || k == null) {
                fail(r, "The recursive call on line " + line(call) + " does not shrink a parameter in a way Vizard "
                        + "can follow (such as n − 1, n / 2, or the half of lo..hi), so no recurrence was formed.");
                return;
            }
            if (key != null && (!key.equals(k) || !mode.equals(s.kind()))) {
                fail(r, "The recursive calls shrink the problem in different ways (" + call + "), so no single "
                        + "recurrence applies.");
                return;
            }
            key = k;
            mode = s.kind();
            amounts.add(s.amount());
        }
        Cost n = Cost.linear(key);
        String nName = "{0}";
        String fText = "O({1})";
        List<Cost> args = List.of(n, work);
        r.recurrenceArgs = args;
        if (mode.equals("sub")) {
            int c = amounts.iterator().next();
            String shrink = amounts.size() == 1 ? (c == 1 ? "T(" + nName + " − 1)" : "T(" + nName + " − " + c + ")")
                    : "T(" + nName + " − c)";
            if (!branching.isConstant()) {
                // calls inside a loop over n: T(n) = n·T(n − 1) + f(n) → n!
                r.recurrence = "T(" + nName + ") = " + nName + " · " + shrink + " + " + fText;
                r.rule = "Unrolling: n · (n − 1) · (n − 2) · … · 1 = n! calls";
                r.time = Cost.of(key, new Factor(true, 0, 0, 0)).times(work);
                r.steps.add(new Step(0, "Recurrence: " + r.recurrence + ": the recursive call is inside a loop that "
                        + "runs about " + nName + " times, and each call shrinks the problem by one.", args));
                r.steps.add(new Step(0, "Unrolled: " + nName + " · (" + nName + " − 1) · … · 1 = " + nName
                        + "! calls, each doing " + fText + " work.", args));
            } else if (calls == 1) {
                r.recurrence = "T(" + nName + ") = " + shrink + " + " + fText;
                r.rule = "Unrolling: n levels of one call each";
                r.time = n.times(work);
                r.steps.add(new Step(0, "Recurrence: " + r.recurrence + ": one recursive call on a problem smaller by "
                        + (amounts.size() == 1 ? c : "a constant") + ".", args));
                r.steps.add(new Step(0, "Unrolled: about " + nName + " calls, each doing " + fText + " work, so "
                        + "T(" + nName + ") = " + nName + " · " + fText + ".", args));
            } else {
                r.recurrence = "T(" + nName + ") = " + (amounts.size() > 1 ? "T(" + nName + " − 1) + T(" + nName
                        + " − 2)" : calls + shrink) + " + " + fText;
                r.rule = "Recursion tree: " + calls + " calls per level, n levels";
                r.time = Cost.of(key, new Factor(false, calls, 0, 0)).times(work);
                r.steps.add(new Step(0, "Recurrence: " + r.recurrence + ": each call makes " + calls
                        + " recursive calls on a problem smaller by a constant.", args));
                r.steps.add(new Step(0, "The recursion tree has about " + nName + " levels and up to " + calls
                        + " times more calls on each level: " + calls + Cost.sup("n") + " calls in all"
                        + (amounts.size() > 1 ? " (an upper bound: with n − 1 and n − 2 the exact growth is "
                        + "φⁿ ≈ 1.618ⁿ)" : "") + ".", args));
            }
            r.space = n.plus(space);
            r.steps.add(new Step(0, "The recursion goes " + nName + " calls deep, so the call stack uses O("
                    + nName + ") space.", args));
            return;
        }
        // divide and conquer
        int b = amounts.iterator().next();
        if (!branching.isConstant() || amounts.size() > 1) {
            fail(r, "The recursive calls divide the problem, but not in a way the master theorem covers.");
            return;
        }
        int a = calls;
        r.recurrence = "T(" + nName + ") = " + (a == 1 ? "" : a) + "T(" + nName + "/" + b + ") + " + fText;
        r.steps.add(new Step(0, "Recurrence: " + r.recurrence + ": " + a + " recursive call" + (a == 1 ? "" : "s")
                + ", each on 1/" + b + " of the problem, plus " + fText + " work of its own.", args));
        Cost.Factor f = polyOf(work, key);
        if (f == null) {
            fail(r, "The work outside the recursive calls, " + work + ", is not a power of the problem size, so "
                    + "the master theorem does not apply.");
            return;
        }
        double p = Math.log(a) / Math.log(b);
        double d = f.halfPower() / 2.0;
        String crit = critical(a, b, p);
        String logba = "log" + subscript(b) + " " + a;
        String fAs = d == 0 && f.logs() == 0 ? "1" : (d == 0 ? "" : "n" + supOf(d)) + (f.logs() > 0 ? (d == 0 ? "" : " ")
                + "log n" : "");
        if (Math.abs(p - d) < 1e-9) {
            r.time = Cost.of(key, new Factor(false, 0, f.halfPower(), f.logs() + 1));
            r.rule = "Master theorem, case 2";
            r.steps.add(new Step(0, "Master theorem with a = " + a + ", b = " + b + ", f(n) = O(" + fAs + "): "
                    + logba + " = " + crit + " equals the exponent of f(n), so every level of the recursion tree does "
                    + "the same work, and there are log n levels: O(" + fAs + ") × log n.", args));
        } else if (p < d) {
            r.time = work;
            r.rule = "Master theorem, case 3";
            r.steps.add(new Step(0, "Master theorem with a = " + a + ", b = " + b + ", f(n) = O(" + fAs + "): "
                    + logba + " = " + crit + " < " + fmt(d) + ", so the work of the first call, O(" + fAs
                    + "), dominates.", args));
        } else {
            int half = (int) Math.ceil(2 * p - 1e-9);
            r.time = Cost.of(key, new Factor(false, 0, half, 0));
            String exponent = Math.abs(p - Math.rint(p)) < 1e-9 ? Cost.sup((int) Math.round(p)) : "^" + crit;
            if (Math.abs(2 * p - Math.rint(2 * p)) > 1e-9) {
                r.timeText = "O(n" + exponent + ")";
            }
            r.rule = "Master theorem, case 1";
            r.steps.add(new Step(0, "Master theorem with a = " + a + ", b = " + b + ", f(n) = O(" + fAs + "): "
                    + logba + " = " + crit + " > " + fmt(d) + ", so the " + a + "-way branching dominates: the "
                    + "leaves of the recursion tree cost O(n" + exponent + ").", args));
        }
        Cost depth = Cost.of(key, new Factor(false, 0, 0, 1));
        r.space = depth.plus(space);
        r.steps.add(new Step(0, "The recursion is log " + nName + " calls deep (the problem halves each time"
                + (b == 2 ? "" : ", divided by " + b) + ").", args));
    }

    private static String supOf(double d) {
        return d == 1 ? "" : Cost.sup((int) d);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static String critical(int a, int b, double p) {
        if (Math.abs(p - Math.rint(p)) < 1e-9) {
            return Long.toString(Math.round(p));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", p);
    }

    private static String subscript(int b) {
        StringBuilder s = new StringBuilder();
        for (char c : Integer.toString(b).toCharArray()) {
            s.append("₀₁₂₃₄₅₆₇₈₉".charAt(c - '0'));
        }
        return s.toString();
    }

    /** f(n) as n^d log^k n in the single symbol key, or null. */
    private static Cost.Factor polyOf(Cost work, String key) {
        if (work.isConstant()) {
            return Cost.Factor.NONE;
        }
        if (!work.symbols().equals(java.util.Set.of(key)) || work.terms().size() != 1) {
            return null;
        }
        Cost.Factor f = work.terms().get(0).factors().get(key);
        return f.factorial() || f.expBase() > 0 ? null : f;
    }

    private void fail(Result r, String why) {
        r.failed = true;
        r.time = null;
        r.steps.add(new Step(0, why, List.of()));
    }

    /** Self-calls executed by one invocation: statements add, an if takes its larger branch. */
    private int callCount(Node n) {
        MethodDeclaration m = n.findAncestor(MethodDeclaration.class).orElse(n instanceof MethodDeclaration md ? md : null);
        return count(n, m);
    }

    private int count(Node n, MethodDeclaration m) {
        if (n instanceof IfStmt s) {
            int c = count(s.getCondition(), m);
            int a = count(s.getThenStmt(), m);
            int b = s.getElseStmt().map(e -> count(e, m)).orElse(0);
            return c + Math.max(a, b);
        }
        if (n instanceof ConditionalExpr c) {
            return count(c.getCondition(), m) + Math.max(count(c.getThenExpr(), m), count(c.getElseExpr(), m));
        }
        if (n instanceof LambdaExpr) {
            return 0;
        }
        int sum = 0;
        if (n instanceof MethodCallExpr call && isSelf(call, m)) {
            sum++;
        }
        for (Node child : n.getChildNodes()) {
            sum += count(child, m);
        }
        return sum;
    }

    /** Product of the counts of loops that enclose a self-call (ONE if none does). */
    private Cost loopFactorOfCalls(MethodDeclaration m) {
        Cost f = Cost.ONE;
        Scope scope = new Scope(m);
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            if (!isSelf(call, m)) {
                continue;
            }
            Node p = call.getParentNode().orElse(null);
            while (p != null && p != m) {
                if (p instanceof ForStmt || p instanceof ForEachStmt || p instanceof WhileStmt || p instanceof DoStmt) {
                    f = Cost.linear("loop"); // any non-constant: the size itself is taken from the recursion
                }
                p = p.getParentNode().orElse(null);
            }
        }
        return f;
    }

    private static boolean isSelf(MethodCallExpr call, MethodDeclaration m) {
        return m != null && call.getNameAsString().equals(m.getNameAsString())
                && (call.getScope().isEmpty() || call.getScope().get().isThisExpr())
                && call.getArguments().size() == m.getParameters().size();
    }

    private Shrink shrinkOf(Expression arg, String param, MethodDeclaration m, Scope scope) {
        Expression e = unwrap(arg);
        if (e instanceof NameExpr n) {
            if (n.getNameAsString().equals(param)) {
                return null;
            }
            if (isMid(n.getNameAsString(), m)) {
                return new Shrink("mid", 2);
            }
            return null;
        }
        if (e instanceof BinaryExpr b) {
            Expression l = unwrap(b.getLeft());
            Expression rgt = unwrap(b.getRight());
            boolean onParam = l instanceof NameExpr ln && ln.getNameAsString().equals(param);
            boolean onMid = l instanceof NameExpr ln2 && isMid(ln2.getNameAsString(), m);
            Integer c = rgt.isIntegerLiteralExpr() ? Integer.valueOf(rgt.asIntegerLiteralExpr().asNumber().intValue()) : null;
            if (onMid && (b.getOperator() == BinaryExpr.Operator.PLUS || b.getOperator() == BinaryExpr.Operator.MINUS)) {
                return new Shrink("mid", 2);
            }
            if (onParam && c != null) {
                switch (b.getOperator()) {
                    case MINUS:
                        return new Shrink("sub", c);
                    case PLUS:
                        return new Shrink("add", c);
                    case DIVIDE:
                        return c > 1 ? new Shrink("div", c) : null;
                    case SIGNED_RIGHT_SHIFT:
                    case UNSIGNED_RIGHT_SHIFT:
                        return new Shrink("div", 1 << c);
                    default:
                        return null;
                }
            }
        }
        if (isMidExpr(e)) {
            return new Shrink("mid", 2);
        }
        return null;
    }

    /** A local initialised to (lo + hi) / 2 or lo + (hi − lo) / 2. */
    private static boolean isMid(String name, MethodDeclaration m) {
        for (VariableDeclarator v : m.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name) && v.getInitializer().isPresent() && isMidExpr(v.getInitializer().get())) {
                return true;
            }
        }
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getTarget().toString().equals(name) && isMidExpr(a.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMidExpr(Expression e) {
        String t = e.toString().replace(" ", "");
        return t.contains("/2") || t.contains(">>1") || t.contains(">>>1");
    }

    /** "range:lo:hi" for the two int parameters a halving recursion narrows. */
    private String rangeKey(MethodDeclaration m, Scope scope) {
        List<String> ints = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            String t = p.getTypeAsString();
            if (t.equals("int") || t.equals("long")) {
                ints.add(p.getNameAsString());
            }
        }
        if (ints.size() < 2) {
            return null;
        }
        // hi − lo, or lo + hi (the middle), between two int parameters
        for (BinaryExpr b : m.findAll(BinaryExpr.class)) {
            if (unwrap(b.getLeft()) instanceof NameExpr l && unwrap(b.getRight()) instanceof NameExpr r
                    && ints.contains(l.getNameAsString()) && ints.contains(r.getNameAsString())
                    && !l.getNameAsString().equals(r.getNameAsString())) {
                if (b.getOperator() == BinaryExpr.Operator.MINUS) {
                    return "range:" + r.getNameAsString() + ":" + l.getNameAsString();
                }
                if (b.getOperator() == BinaryExpr.Operator.PLUS) {
                    return "range:" + l.getNameAsString() + ":" + r.getNameAsString();
                }
            }
        }
        // for (int j = low; j < high; j++): a loop from one int parameter to another
        for (ForStmt f : m.findAll(ForStmt.class)) {
            if (f.getInitialization().size() == 1 && f.getInitialization().get(0) instanceof VariableDeclarationExpr v
                    && v.getVariables().get(0).getInitializer().map(i -> unwrap(i) instanceof NameExpr in
                    && ints.contains(in.getNameAsString())).orElse(false) && f.getCompare().isPresent()
                    && unwrap(f.getCompare().get()) instanceof BinaryExpr cmp && unwrap(cmp.getRight()) instanceof NameExpr r
                    && ints.contains(r.getNameAsString())) {
                String lo = unwrap(v.getVariables().get(0).getInitializer().get()).toString();
                if (!lo.equals(r.getNameAsString())) {
                    return "range:" + lo + ":" + r.getNameAsString();
                }
            }
        }
        // lo <= hi / lo < hi as the base case or loop condition
        for (BinaryExpr b : m.findAll(BinaryExpr.class)) {
            if (isRelational(b.getOperator()) && unwrap(b.getLeft()) instanceof NameExpr l
                    && unwrap(b.getRight()) instanceof NameExpr r && ints.contains(l.getNameAsString())
                    && ints.contains(r.getNameAsString()) && !l.getNameAsString().equals(r.getNameAsString())) {
                boolean lFirst = ints.indexOf(l.getNameAsString()) < ints.indexOf(r.getNameAsString());
                return lFirst ? "range:" + l.getNameAsString() + ":" + r.getNameAsString()
                        : "range:" + r.getNameAsString() + ":" + l.getNameAsString();
            }
        }
        return null;
    }

    /** An expression made only of positions inside the range (lo, hi, mid, i = lo ...) and constants. */
    private static boolean isPositional(Expression e, Scope scope) {
        if (scope == null || scope.positions.isEmpty() || e == null) {
            return false;
        }
        if (!e.findAll(MethodCallExpr.class).isEmpty() || !e.findAll(ArrayAccessExpr.class).isEmpty()
                || !e.findAll(FieldAccessExpr.class).isEmpty()) {
            return false;
        }
        List<NameExpr> names = new ArrayList<>(e.findAll(NameExpr.class));
        if (e instanceof NameExpr n && !names.contains(n)) {
            names.add(n);
        }
        return !names.isEmpty() && names.stream().allMatch(n -> scope.positions.contains(n.getNameAsString()));
    }

    /** hi − lo when both are positions in the range; otherwise as large as either. */
    private Cost difference(Expression hi, Expression lo, Scope scope) {
        if (scope != null && scope.range != null && isPositional(hi, scope) && isPositional(lo, scope)) {
            return Cost.linear(scope.range);
        }
        return sizeOf(hi, scope).plus(sizeOf(lo, scope));
    }

    /** For f(i + 1): the X in a base case "if (i == X)" / "if (i >= X)". */
    private static Expression baseLimit(MethodDeclaration m, String p) {
        for (IfStmt s : m.findAll(IfStmt.class)) {
            for (BinaryExpr b : s.getCondition().findAll(BinaryExpr.class)) {
                boolean cmp = b.getOperator() == BinaryExpr.Operator.EQUALS
                        || b.getOperator() == BinaryExpr.Operator.GREATER_EQUALS
                        || b.getOperator() == BinaryExpr.Operator.GREATER;
                if (cmp && unwrap(b.getLeft()) instanceof NameExpr n && n.getNameAsString().equals(p)) {
                    return b.getRight();
                }
            }
        }
        return null;
    }

    // ---------- statements ------------------------------------------------------------------

    /** Sizes known in a method: loop variables' ranges, locals' initial values, declared types. */
    private final class Scope {
        final MethodDeclaration method;
        final Map<String, Cost> values = new HashMap<>();   // int locals: the size of their initial value
        final Map<String, Cost> lengths = new HashMap<>();  // arrays/lists: their length
        final Map<String, String> types = new HashMap<>();
        final Map<String, Expression> inits = new HashMap<>();
        /** Index variables inside lo..hi: a difference of two of them is the size of the range. */
        final Set<String> positions = new java.util.HashSet<>();
        String range;

        Scope(MethodDeclaration method) {
            this.method = method;
        }

        String typeOf(String name) {
            String t = types.get(name);
            return t != null ? t : fieldTypes.get(name);
        }
    }

    private final class Walker {
        final MethodDeclaration method;
        final Scope scope;
        final Result result;
        final List<MethodCallExpr> selfCalls = new ArrayList<>();
        Cost allocated = Cost.ONE;

        Walker(MethodDeclaration method, Scope scope, Result result) {
            this.method = method;
            this.scope = scope;
            this.result = result;
        }

        void step(int depth, String text, Cost... costs) {
            result.steps.add(new Step(depth, text, List.of(costs)));
        }

        Cost stmt(Statement s, int depth) {
            if (s instanceof BlockStmt b) {
                Cost sum = Cost.ONE;
                for (Statement child : b.getStatements()) {
                    sum = sum.plus(stmt(child, depth));
                }
                return sum;
            }
            if (s instanceof ExpressionStmt e) {
                declare(e.getExpression());
                return expr(e.getExpression(), depth);
            }
            if (s instanceof IfStmt i) {
                Cost c = expr(i.getCondition(), depth);
                Cost a = stmt(i.getThenStmt(), depth);
                Cost b = i.getElseStmt().map(x -> stmt(x, depth)).orElse(Cost.ONE);
                return c.plus(a.max(b));
            }
            if (s instanceof ForStmt f) {
                return forLoop(f, depth);
            }
            if (s instanceof ForEachStmt f) {
                Cost n = lengthOf(f.getIterable(), scope);
                String var = f.getVariable().getVariables().get(0).getNameAsString();
                step(depth, "Line " + line(f) + ": for-each over " + f.getIterable() + " → {0} iterations"
                        + (depth > 0 ? " (nested, so multiplied by the loops around it)" : ""), n);
                scope.values.put(var, Cost.linear("value:" + f.getIterable()));
                Cost body = stmt(f.getBody(), depth + 1);
                return n.times(body).plus(expr(f.getIterable(), depth));
            }
            if (s instanceof WhileStmt w) {
                Cost n = whileCount(w.getCondition(), w.getBody(), line(w), depth);
                Cost body = stmt(w.getBody(), depth + 1).plus(expr(w.getCondition(), depth + 1));
                return n.times(body);
            }
            if (s instanceof DoStmt d) {
                Cost n = whileCount(d.getCondition(), d.getBody(), line(d), depth);
                Cost body = stmt(d.getBody(), depth + 1).plus(expr(d.getCondition(), depth + 1));
                return n.times(body);
            }
            if (s instanceof ReturnStmt r) {
                return r.getExpression().map(x -> expr(x, depth)).orElse(Cost.ONE);
            }
            if (s instanceof ThrowStmt t) {
                return expr(t.getExpression(), depth);
            }
            if (s instanceof SwitchStmt sw) {
                Cost c = expr(sw.getSelector(), depth);
                for (var entry : sw.getEntries()) {
                    for (Statement x : entry.getStatements()) {
                        c = c.plus(stmt(x, depth));
                    }
                }
                return c;
            }
            if (s instanceof TryStmt t) {
                Cost c = stmt(t.getTryBlock(), depth);
                for (var cc : t.getCatchClauses()) {
                    c = c.plus(stmt(cc.getBody(), depth));
                }
                if (t.getFinallyBlock().isPresent()) {
                    c = c.plus(stmt(t.getFinallyBlock().get(), depth));
                }
                return c;
            }
            if (s instanceof LabeledStmt l) {
                return stmt(l.getStatement(), depth);
            }
            return Cost.ONE;
        }

        /** Remember what a local stands for: int n = arr.length; int[] b = new int[n]; int mid = ... */
        void declare(Expression e) {
            if (e instanceof VariableDeclarationExpr v) {
                for (VariableDeclarator d : v.getVariables()) {
                    remember(d.getNameAsString(), d.getTypeAsString(), d.getInitializer().orElse(null));
                }
            } else if (e instanceof AssignExpr a && a.getOperator() == AssignExpr.Operator.ASSIGN
                    && a.getTarget() instanceof NameExpr n && !scope.values.containsKey(n.getNameAsString())
                    && scope.types.containsKey(n.getNameAsString())) {
                remember(n.getNameAsString(), scope.types.get(n.getNameAsString()), a.getValue());
            }
        }

        void remember(String name, String type, Expression init) {
            scope.types.put(name, type);
            if (init == null) {
                return;
            }
            scope.inits.put(name, init);
            if (isIntType(type) && isPositional(init, scope)) {
                scope.positions.add(name);
            }
            if (isIntType(type)) {
                Cost v = sizeOf(init, scope);
                if (v.isConstant() && unwrap(init).isIntegerLiteralExpr()
                        && name.matches("n|m|N|M|V|E|size|len|length|num|count|rows|cols|limit|total")) {
                    // int n = 5; in main: the input size, written as a constant
                    v = Cost.linear("var:" + name);
                }
                v = v.symbols().stream().anyMatch(k -> k.startsWith("expr:")) ? Cost.linear("var:" + name) : v;
                scope.values.put(name, v);
                Expression bare = unwrap(init);
                boolean exactSize = bare instanceof FieldAccessExpr fa && fa.getNameAsString().equals("length")
                        || bare instanceof MethodCallExpr mc && (mc.getNameAsString().equals("size")
                        || mc.getNameAsString().equals("length")) && mc.getArguments().isEmpty();
                if (exactSize && v.singleSymbol() != null && v.equals(Cost.linear(v.singleSymbol()))) {
                    result.preferred.putIfAbsent(v.singleSymbol(), name);
                }
            } else if (init.isArrayInitializerExpr()) {
                // a literal array: its length is a size of its own (len:name)
            } else if (init instanceof com.github.javaparser.ast.expr.ObjectCreationExpr o && o.getArguments().isEmpty()
                    && isCollectionType(type)) {
                scope.lengths.put(name, Cost.linear("size:" + name)); // filled by the code: its own size
            } else if (type.endsWith("]") || isCollectionType(type) || type.equals("String")) {
                scope.lengths.put(name, lengthOf(init, scope));
            }
        }

        Cost forLoop(ForStmt f, int depth) {
            String var = null;
            Expression init = null;
            Cost initCost = Cost.ONE;
            for (Expression e : f.getInitialization()) {
                declare(e);
                initCost = initCost.plus(expr(e, depth));
                if (e instanceof VariableDeclarationExpr v && !v.getVariables().isEmpty()) {
                    var = v.getVariables().get(0).getNameAsString();
                    init = v.getVariables().get(0).getInitializer().orElse(null);
                } else if (e instanceof AssignExpr a && a.getTarget() instanceof NameExpr n) {
                    var = n.getNameAsString();
                    init = a.getValue();
                }
            }
            if (var == null) {
                for (Expression u : f.getUpdate()) {
                    String t = updatedVar(u);
                    if (t != null) {
                        var = t;
                        break;
                    }
                }
            }
            LoopCount lc = var == null || f.getCompare().isEmpty() ? null
                    : countFor(var, init, f.getCompare().get(), f.getUpdate(), f.getBody(), scope);
            Cost n;
            if (lc == null) {
                n = Cost.linear("loop:" + line(f));
                step(depth, "Line " + line(f) + ": a for loop whose bound Vizard cannot read; its iterations are "
                        + "called {0}", n);
            } else {
                n = lc.count;
                step(depth, "Line " + line(f) + ": for " + var + " " + lc.how + " → "
                        + (n.isConstant() ? "a constant number of" : "{0}") + " iterations"
                        + (depth > 0 ? " (nested, so multiplied by the loops around it)" : ""), n);
                scope.values.put(var, lc.range);
            }
            if (var != null && init != null && isPositional(init, scope)) {
                scope.positions.add(var);
            }
            Cost body = stmt(f.getBody(), depth + 1);
            for (Expression u : f.getUpdate()) {
                body = body.plus(expr(u, depth + 1));
            }
            if (f.getCompare().isPresent()) {
                body = body.plus(expr(f.getCompare().get(), depth + 1));
            }
            return initCost.plus(n.times(body));
        }

        Cost whileCount(Expression cond, Statement body, int line, int depth) {
            LoopCount lc = countWhile(cond, body, scope);
            if (lc == null) {
                Cost n = Cost.linear("loop:" + line);
                step(depth, "Line " + line + ": a while loop whose number of iterations Vizard cannot read from "
                        + "the code; it is called {0}", n);
                return n;
            }
            step(depth, "Line " + line + ": while " + cond + " — " + lc.how + " → "
                    + (lc.count.isConstant() ? "a constant number of" : "{0}") + " iterations"
                    + (depth > 0 ? " (nested, so multiplied by the loops around it)" : ""), lc.count);
            return lc.count;
        }

        /** Work done by evaluating an expression: the calls in it, and arrays it allocates. */
        Cost expr(Expression e, int depth) {
            Cost c = Cost.ONE;
            for (MethodCallExpr call : e.findAll(MethodCallExpr.class)) {
                if (call.findAncestor(LambdaExpr.class).isPresent() && !e.findAncestor(LambdaExpr.class).isPresent()) {
                    continue;
                }
                c = c.plus(call(call, depth));
            }
            if (e instanceof MethodCallExpr call0 && !e.findAll(MethodCallExpr.class).contains(call0)) {
                c = c.plus(call(call0, depth));
            }
            for (ArrayCreationExpr a : e.findAll(ArrayCreationExpr.class)) {
                Cost size = Cost.ONE;
                for (var level : a.getLevels()) {
                    if (level.getDimension().isPresent()) {
                        size = size.times(sizeOf(level.getDimension().get(), scope));
                    }
                }
                if (!size.isConstant()) {
                    step(depth, "Line " + line(a) + ": new " + a.getElementType() + " array of {0} cells (Java fills "
                            + "it with zeros, so creating it costs as much as its size)", size);
                    allocated = allocated.plus(size);
                }
                c = c.plus(size);
            }
            return c;
        }

        Cost call(MethodCallExpr call, int depth) {
            if (isSelf(call, method)) {
                selfCalls.add(call);
                return Cost.ONE;
            }
            Optional<MethodDeclaration> target = userMethod(call);
            if (target.isPresent()) {
                MethodDeclaration callee = target.get();
                if (inProgress.contains(callee)) {
                    step(depth, "Line " + line(call) + ": " + call.getNameAsString() + "() calls back into a method "
                            + "that is still being analysed (mutual recursion); that call is counted as O(1)");
                    return Cost.ONE;
                }
                Result r = resultOf(callee);
                if (r.time == null) {
                    step(depth, "Line " + line(call) + ": " + call.getNameAsString() + "() has no estimate, so it is "
                            + "counted as O(1) here");
                    return Cost.ONE;
                }
                Cost c = r.time.substitute(k -> replacement(k, callee, call));
                if (r.space != null) {
                    allocated = allocated.plus(r.space.substitute(k -> replacement(k, callee, call)));
                }
                if (!c.isConstant()) {
                    step(depth, "Line " + line(call) + ": calls " + call.getNameAsString() + "() → O({0})", c);
                }
                return c;
            }
            return library(call, depth);
        }

        /** A callee's symbol in terms of this call's arguments. */
        Cost replacement(String key, MethodDeclaration callee, MethodCallExpr call) {
            int colon = key.indexOf(':');
            String kind = key.substring(0, colon);
            String rest = key.substring(colon + 1);
            if (kind.equals("range")) {
                String[] lohi = rest.split(":");
                int lo = paramIndex(callee, lohi[0]);
                int hi = paramIndex(callee, lohi[1]);
                if (lo >= 0 && hi >= 0 && hi < call.getArguments().size()) {
                    return difference(call.getArgument(hi), call.getArgument(lo), scope);
                }
                return null;
            }
            int i = paramIndex(callee, rest);
            if (i < 0 || i >= call.getArguments().size()) {
                return null;
            }
            Expression arg = call.getArgument(i);
            return switch (kind) {
                case "var" -> sizeOf(arg, scope);
                case "len" -> lengthOf(arg, scope);
                case "value" -> unwrap(arg) instanceof NameExpr n ? Cost.linear("value:" + n.getNameAsString()) : null;
                case "nodes" -> unwrap(arg) instanceof NameExpr n ? Cost.linear("nodes:" + n.getNameAsString()) : null;
                default -> null;
            };
        }

        /** Library calls with a known cost; everything else is O(1). */
        Cost library(MethodCallExpr call, int depth) {
            String name = call.getNameAsString();
            String scopeText = call.getScope().map(Object::toString).orElse("");
            Cost c = null;
            String what = null;
            if ((scopeText.equals("Arrays") && name.equals("sort") || scopeText.equals("Collections") && name.equals("sort"))
                    && call.getArguments().isNonEmpty()) {
                Cost n = lengthOf(call.getArgument(0), scope);
                c = n.times(n.log());
                what = scopeText + ".sort sorts {1} elements";
                return logged(depth, call, c, what, n);
            }
            if (scopeText.equals("Arrays") && call.getArguments().isNonEmpty() && Set.of("fill", "copyOf",
                    "copyOfRange", "toString", "equals", "deepToString", "asList", "stream", "binarySearch").contains(name)) {
                Cost n = lengthOf(call.getArgument(0), scope);
                if (name.equals("binarySearch")) {
                    c = n.log();
                    what = "Arrays.binarySearch halves {1} elements: O(log n)";
                } else {
                    c = n;
                    what = "Arrays." + name + " visits all {1} elements";
                }
                return logged(depth, call, c, what, n);
            }
            if (scopeText.equals("System") && name.equals("arraycopy") && call.getArguments().size() == 5) {
                Cost n = sizeOf(call.getArgument(4), scope);
                return logged(depth, call, n, "System.arraycopy copies {1} elements", n);
            }
            if (call.getScope().isPresent() && unwrap(call.getScope().get()) instanceof NameExpr sc) {
                String type = scope.typeOf(sc.getNameAsString());
                Cost n = lengthOf(sc, scope);
                if (type != null && (type.contains("List") || type.contains("Deque") && !type.contains("Array")
                        || type.endsWith("[]")) && Set.of("contains", "indexOf", "lastIndexOf", "remove").contains(name)
                        && !(name.equals("remove") && type.contains("Deque"))) {
                    boolean byIndex = name.equals("remove") && type.contains("ArrayList")
                            && call.getArguments().size() == 1;
                    return logged(depth, call, n, sc + "." + name + (byIndex ? " shifts" : " scans")
                            + " up to {1} elements of the list", n);
                }
                if (type != null && (type.startsWith("PriorityQueue") || type.startsWith("TreeMap")
                        || type.startsWith("TreeSet")) && Set.of("add", "offer", "poll", "remove", "put", "get",
                        "containsKey", "contains", "floorKey", "ceilingKey").contains(name)) {
                    return logged(depth, call, n.log(), sc + "." + name + " on a " + type.replaceAll("<.*", "")
                            + " costs O(log {1}) for its {1} elements", n);
                }
                if (type != null && type.equals("String") && Set.of("substring", "toCharArray", "indexOf", "contains",
                        "equals", "compareTo", "replace", "split", "toUpperCase", "toLowerCase", "reverse").contains(name)) {
                    return logged(depth, call, n, sc + "." + name + " goes through up to {1} characters", n);
                }
                if (type != null && type.contains("StringBuilder") && name.equals("reverse")) {
                    return logged(depth, call, n, sc + ".reverse goes through {1} characters", n);
                }
            }
            return Cost.ONE;
        }

        Cost logged(int depth, MethodCallExpr call, Cost c, String what, Cost n) {
            if (!c.isConstant()) {
                step(depth, "Line " + line(call) + ": " + what + " → O({0})", c, n);
            }
            return c;
        }
    }

    // ---------- how many times a loop runs ----------------------------------------------------

    /** count = iterations; range = how large the loop variable gets; how = the reason in words. */
    private record LoopCount(Cost count, Cost range, String how) {
    }

    private LoopCount countFor(String var, Expression init, Expression compare, NodeList<Expression> updates,
                               Statement body, Scope scope) {
        // for (Node x = head; x != null; x = x.next)
        Expression c = unwrap(compare);
        if (c instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.NOT_EQUALS
                && b.getRight() instanceof NullLiteralExpr && unwrap(b.getLeft()).toString().equals(var)) {
            for (Expression u : updates) {
                if (u instanceof AssignExpr a && a.getTarget().toString().equals(var)
                        && a.getValue() instanceof FieldAccessExpr f && f.getScope().toString().equals(var)) {
                    String root = init instanceof NameExpr r ? r.getNameAsString() : var;
                    return new LoopCount(Cost.linear("nodes:" + root), Cost.ONE,
                            "follows ." + f.getNameAsString() + " links: once per node");
                }
            }
        }
        String kind = null;
        for (Expression u : updates) {
            if (var.equals(updatedVar(u))) {
                kind = updateKind(u);
            }
        }
        if (kind == null) {
            kind = bodyUpdate(var, body);
        }
        return count(var, init, compare, kind, scope);
    }

    private LoopCount count(String var, Expression init, Expression compare, String kind, Scope scope) {
        if (kind == null) {
            return null;
        }
        BinaryExpr rel = relationOn(var, compare);
        if (rel == null) {
            return null;
        }
        boolean varLeft = mentions(rel.getLeft(), var);
        Expression varSide = unwrap(varLeft ? rel.getLeft() : rel.getRight());
        Expression other = varLeft ? rel.getRight() : rel.getLeft();
        Cost bound = sizeOf(other, scope);
        Cost start = init == null ? valueOf(var, scope) : sizeOf(init, scope);
        boolean squared = varSide instanceof BinaryExpr vb && vb.getOperator() == BinaryExpr.Operator.MULTIPLY;
        Cost range = bound.plus(start);
        Expression startExpr = init != null ? init : new NameExpr(var);
        if (isPositional(other, scope) && isPositional(startExpr, scope) && (kind.equals("inc") || kind.equals("dec"))) {
            Cost n = Cost.linear(scope.range);
            return new LoopCount(n, range, (kind.equals("inc") ? "goes up" : "goes down") + " by a constant from "
                    + startExpr + " to " + other + ", positions inside the range");
        }
        String boundText = other.toString();
        return switch (kind) {
            case "inc" -> squared
                    ? new LoopCount(bound.sqrt(), bound.sqrt(), "grows by a constant while " + var + "·" + var + " ≤ "
                    + boundText + ": √ of " + boundText)
                    : new LoopCount(bound, range, "goes up by a constant until it reaches " + boundText);
            case "dec" -> new LoopCount(start.isConstant() ? bound : start, range, "goes down by a constant from "
                    + (init == null ? var : init.toString()) + " to " + boundText);
            case "mul" -> new LoopCount(bound.log(), range, "is multiplied each time until it reaches " + boundText
                    + ": log of " + boundText);
            case "div" -> new LoopCount((start.isConstant() ? bound : start).log(), range, "is divided each time, "
                    + "starting from " + (init == null ? var : init.toString()) + ": log of that");
            default -> null;
        };
    }

    private LoopCount countWhile(Expression cond, Statement body, Scope scope) {
        Expression c = unwrap(cond);
        // linked list walk: while (cur != null) { cur = cur.next; }
        for (BinaryExpr b : c.findAll(BinaryExpr.class)) {
            if (b.getOperator() == BinaryExpr.Operator.NOT_EQUALS && b.getRight() instanceof NullLiteralExpr
                    && unwrap(b.getLeft()) instanceof NameExpr n) {
                String name = n.getNameAsString();
                for (AssignExpr a : body.findAll(AssignExpr.class)) {
                    if (a.getTarget().toString().equals(name) && a.getValue() instanceof FieldAccessExpr f
                            && f.getScope().toString().equals(name)) {
                        Expression from = scope.inits.get(name);
                        String root = from instanceof NameExpr r ? r.getNameAsString() : name;
                        return new LoopCount(Cost.linear("nodes:" + root), Cost.ONE,
                                name + " follows ." + f.getNameAsString() + " links: once per node");
                    }
                }
            }
        }
        // worklist: while (!q.isEmpty())
        for (MethodCallExpr call : c.findAll(MethodCallExpr.class)) {
            if ((call.getNameAsString().equals("isEmpty") || call.getNameAsString().equals("size"))
                    && call.getScope().isPresent()) {
                String q = call.getScope().get().toString();
                return new LoopCount(Cost.linear("items:" + q), Cost.ONE, "runs until " + q + " is empty: once per "
                        + "item ever added to it");
            }
        }
        // binary search: while (lo <= hi) { mid = (lo + hi) / 2; lo = mid + 1 / hi = mid − 1 }
        BinaryExpr rel = c instanceof BinaryExpr b0 && isRelational(b0.getOperator()) ? b0
                : c.findAll(BinaryExpr.class).stream().filter(b -> isRelational(b.getOperator())).findFirst().orElse(null);
        if (rel == null) {
            return null;
        }
        Expression l = unwrap(rel.getLeft());
        Expression r = unwrap(rel.getRight());
        if (l instanceof NameExpr lo && r instanceof NameExpr hi) {
            boolean halves = false;
            for (AssignExpr a : body.findAll(AssignExpr.class)) {
                String t = a.getTarget().toString();
                if ((t.equals(lo.getNameAsString()) || t.equals(hi.getNameAsString()))
                        && (isMidExpr(a.getValue()) || unwrap(a.getValue()).findAll(NameExpr.class).stream()
                        .anyMatch(n -> isMidLocal(n.getNameAsString(), body)))) {
                    halves = true;
                }
            }
            if (halves) {
                Cost size = valueOf(hi.getNameAsString(), scope).plus(valueOf(lo.getNameAsString(), scope));
                return new LoopCount(size.log(), size, "the range " + lo + ".." + hi + " is halved each time "
                        + "(mid = the middle): log of its size");
            }
        }
        for (String var : List.of(l instanceof NameExpr ln ? ln.getNameAsString() : "",
                r instanceof NameExpr rn ? rn.getNameAsString() : "")) {
            if (var.isEmpty()) {
                continue;
            }
            String kind = bodyUpdate(var, body);
            if (kind != null) {
                return count(var, null, rel, kind, scope);
            }
        }
        // while (n > 0) { ... n /= 10 } with the variable inside an expression
        for (NameExpr n : rel.findAll(NameExpr.class)) {
            String kind = bodyUpdate(n.getNameAsString(), body);
            if (kind != null) {
                return count(n.getNameAsString(), null, rel, kind, scope);
            }
        }
        return null;
    }

    private static boolean isMidLocal(String name, Statement body) {
        for (VariableDeclarator v : body.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name) && v.getInitializer().map(ComplexityEstimator::isMidExpr).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** "inc", "dec", "mul", "div" for i++, i -= 2, i *= 2, n /= 10, or null. */
    private static String updateKind(Expression u) {
        u = unwrap(u);
        if (u instanceof UnaryExpr ue) {
            return switch (ue.getOperator()) {
                case POSTFIX_INCREMENT, PREFIX_INCREMENT -> "inc";
                case POSTFIX_DECREMENT, PREFIX_DECREMENT -> "dec";
                default -> null;
            };
        }
        if (u instanceof AssignExpr a) {
            String target = a.getTarget().toString();
            switch (a.getOperator()) {
                case PLUS:
                    return constantish(a.getValue()) ? "inc" : null;
                case MINUS:
                    return constantish(a.getValue()) ? "dec" : null;
                case MULTIPLY:
                case LEFT_SHIFT:
                    return "mul";
                case DIVIDE:
                case SIGNED_RIGHT_SHIFT:
                case UNSIGNED_RIGHT_SHIFT:
                    return "div";
                case ASSIGN:
                    if (unwrap(a.getValue()) instanceof BinaryExpr b && unwrap(b.getLeft()).toString().equals(target)) {
                        return switch (b.getOperator()) {
                            case PLUS -> constantish(b.getRight()) ? "inc" : null;
                            case MINUS -> constantish(b.getRight()) ? "dec" : null;
                            case MULTIPLY, LEFT_SHIFT -> "mul";
                            case DIVIDE, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> "div";
                            default -> null;
                        };
                    }
                    return null;
                default:
                    return null;
            }
        }
        return null;
    }

    private static boolean constantish(Expression e) {
        e = unwrap(e);
        return e.isIntegerLiteralExpr() || e.isLongLiteralExpr() || e instanceof NameExpr n
                && Character.isUpperCase(n.getNameAsString().charAt(0));
    }

    private static String updatedVar(Expression u) {
        u = unwrap(u);
        if (u instanceof UnaryExpr ue && ue.getExpression() instanceof NameExpr n) {
            return n.getNameAsString();
        }
        if (u instanceof AssignExpr a && a.getTarget() instanceof NameExpr n) {
            return n.getNameAsString();
        }
        return null;
    }

    /** How the body changes a variable (the last recognised update wins). */
    private static String bodyUpdate(String var, Statement body) {
        String kind = null;
        for (Expression e : body.findAll(Expression.class)) {
            if ((e instanceof UnaryExpr || e instanceof AssignExpr) && var.equals(updatedVar(e))) {
                String k = updateKind(e);
                if (k != null) {
                    kind = k;
                }
            }
        }
        return kind;
    }

    private static BinaryExpr relationOn(String var, Expression compare) {
        Expression c = unwrap(compare);
        if (c instanceof BinaryExpr b && isRelational(b.getOperator()) && mentions(b, var)) {
            return b;
        }
        for (BinaryExpr b : c.findAll(BinaryExpr.class)) {
            if (isRelational(b.getOperator()) && mentions(b, var)) {
                return b;
            }
        }
        return null;
    }

    private static boolean isRelational(BinaryExpr.Operator op) {
        return op == BinaryExpr.Operator.LESS || op == BinaryExpr.Operator.LESS_EQUALS
                || op == BinaryExpr.Operator.GREATER || op == BinaryExpr.Operator.GREATER_EQUALS
                || op == BinaryExpr.Operator.NOT_EQUALS;
    }

    private static boolean mentions(Node e, String var) {
        if (e instanceof NameExpr n && n.getNameAsString().equals(var)) {
            return true;
        }
        return e.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(var));
    }

    // ---------- sizes ---------------------------------------------------------------------------

    /** How large the number an expression evaluates to can be, in size symbols. */
    private Cost sizeOf(Expression e, Scope scope) {
        e = unwrap(e);
        if (e.isIntegerLiteralExpr() || e.isLongLiteralExpr() || e.isCharLiteralExpr() || e.isDoubleLiteralExpr()
                || e.isBooleanLiteralExpr()) {
            return Cost.ONE;
        }
        if (e instanceof NameExpr n) {
            return valueOf(n.getNameAsString(), scope);
        }
        if (e instanceof FieldAccessExpr f) {
            if (f.getNameAsString().equals("length")) {
                return lengthOf(f.getScope(), scope);
            }
            if (f.getScope().isThisExpr()) {
                return valueOf(f.getNameAsString(), scope);
            }
            if (f.getNameAsString().equals("MAX_VALUE") || f.getNameAsString().equals("MIN_VALUE")) {
                return Cost.ONE;
            }
            return Cost.linear("var:" + f.getNameAsString());
        }
        if (e instanceof MethodCallExpr call) {
            String name = call.getNameAsString();
            if ((name.equals("size") || name.equals("length")) && call.getScope().isPresent()) {
                return lengthOf(call.getScope().get(), scope);
            }
            if (call.getScope().map(Object::toString).orElse("").equals("Math")) {
                return switch (name) {
                    case "max", "min" -> call.getArguments().stream().map(a -> sizeOf(a, scope))
                            .reduce(Cost.ONE, Cost::plus);
                    case "sqrt" -> sizeOf(call.getArgument(0), scope).sqrt();
                    case "abs" -> sizeOf(call.getArgument(0), scope);
                    default -> Cost.linear("expr:" + e);
                };
            }
            return Cost.linear("expr:" + e);
        }
        if (e instanceof BinaryExpr b) {
            Cost l = sizeOf(b.getLeft(), scope);
            Cost r = sizeOf(b.getRight(), scope);
            return switch (b.getOperator()) {
                case PLUS -> l.plus(r);
                case MINUS -> isPositional(b.getLeft(), scope) && isPositional(b.getRight(), scope)
                        ? Cost.linear(scope.range) : l.plus(r);
                case MULTIPLY -> l.times(r);
                case DIVIDE, REMAINDER, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> l;
                case LEFT_SHIFT -> b.getRight().isIntegerLiteralExpr() ? l : Cost.linear("expr:" + e);
                default -> Cost.ONE;
            };
        }
        if (e instanceof UnaryExpr u) {
            return sizeOf(u.getExpression(), scope);
        }
        if (e instanceof ConditionalExpr c) {
            return sizeOf(c.getThenExpr(), scope).plus(sizeOf(c.getElseExpr(), scope));
        }
        if (e instanceof ArrayAccessExpr a) {
            Expression root = a.getName();
            while (root instanceof ArrayAccessExpr inner) {
                root = inner.getName();
            }
            return Cost.linear("value:" + root);
        }
        return Cost.linear("expr:" + e);
    }

    private Cost valueOf(String name, Scope scope) {
        if (scope != null) {
            Cost v = scope.values.get(name);
            if (v != null) {
                return v;
            }
        }
        return Cost.linear("var:" + name);
    }

    /** How many elements an array, list or string holds. */
    private Cost lengthOf(Expression e, Scope scope) {
        e = unwrap(e);
        if (e instanceof NameExpr n) {
            String name = n.getNameAsString();
            if (scope != null && scope.lengths.containsKey(name)) {
                return scope.lengths.get(name);
            }
            if (fieldLengths.containsKey(name) && (scope == null || !scope.types.containsKey(name))) {
                return fieldLengths.get(name);
            }
            String type = scope == null ? fieldTypes.get(name) : scope.typeOf(name);
            if (type != null && isCollectionType(type) && !type.contains("StringBuilder")) {
                return Cost.linear("size:" + name);
            }
            if (type != null && (type.equals("String") || type.contains("StringBuilder"))) {
                return Cost.linear("chars:" + name);
            }
            return Cost.linear("len:" + name);
        }
        if (e instanceof ArrayCreationExpr a) {
            if (!a.getLevels().isEmpty() && a.getLevels().get(0).getDimension().isPresent()) {
                return sizeOf(a.getLevels().get(0).getDimension().get(), scope);
            }
            return a.getInitializer().map(i -> Cost.ONE).orElse(Cost.ONE);
        }
        if (e instanceof ArrayAccessExpr a) {
            Expression root = a.getName();
            while (root instanceof ArrayAccessExpr inner) {
                root = inner.getName();
            }
            return Cost.linear("len:" + root + "[]");
        }
        if (e instanceof FieldAccessExpr f) {
            return f.getScope().isThisExpr() ? lengthOf(new NameExpr(f.getNameAsString()), scope)
                    : Cost.linear("len:" + e);
        }
        if (e instanceof MethodCallExpr call && call.getScope().isPresent()) {
            String name = call.getNameAsString();
            if (Set.of("toCharArray", "clone", "substring", "trim", "toUpperCase", "toLowerCase", "keySet", "values",
                    "entrySet", "chars", "split").contains(name)) {
                return lengthOf(call.getScope().get(), scope);
            }
            if (call.getScope().get().toString().equals("Arrays") && call.getArguments().isNonEmpty()) {
                return name.equals("copyOf") && call.getArguments().size() > 1
                        ? sizeOf(call.getArgument(1), scope) : lengthOf(call.getArgument(0), scope);
            }
            if (name.equals("get") && unwrap(call.getScope().get()) instanceof NameExpr g) {
                return Cost.linear("len:" + g.getNameAsString() + "[]");
            }
            return Cost.linear("len:" + e);
        }
        if (e instanceof com.github.javaparser.ast.expr.ObjectCreationExpr o && o.getArguments().isNonEmpty()) {
            // new ArrayList<>(other), new StringBuilder(s)
            Expression a = o.getArgument(0);
            return a.isIntegerLiteralExpr() ? Cost.ONE : lengthOf(a, scope);
        }
        if (e instanceof com.github.javaparser.ast.expr.ObjectCreationExpr) {
            return Cost.ONE; // an empty collection that the code fills
        }
        if (e instanceof com.github.javaparser.ast.expr.StringLiteralExpr) {
            return Cost.ONE;
        }
        return Cost.linear("len:" + e);
    }

    private static Expression unwrap(Expression e) {
        while (true) {
            if (e instanceof EnclosedExpr en) {
                e = en.getInner();
            } else if (e instanceof CastExpr c) {
                e = c.getExpression();
            } else {
                return e;
            }
        }
    }

    private static boolean isIntType(String t) {
        return Set.of("int", "long", "short", "byte", "Integer", "Long").contains(t);
    }

    private static boolean isCollectionType(String t) {
        return t.contains("List") || t.contains("Set") || t.contains("Map") || t.contains("Queue") || t.contains("Deque")
                || t.contains("Stack") || t.contains("StringBuilder");
    }

    private Optional<MethodDeclaration> userMethod(MethodCallExpr call) {
        if (call.getScope().isPresent()) {
            Expression s = call.getScope().get();
            // Main.helper(...) or this.helper(...); obj.method() on a user class is not followed
            if (!s.isThisExpr() && !(s instanceof NameExpr n && Character.isUpperCase(n.getNameAsString().charAt(0))
                    && cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                    .anyMatch(c -> c.getNameAsString().equals(n.getNameAsString())))) {
                return Optional.empty();
            }
        }
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(call.getNameAsString())
                        && m.getParameters().size() == call.getArguments().size() && m.getBody().isPresent())
                .findFirst();
    }

    private static int paramIndex(MethodDeclaration m, String name) {
        for (int i = 0; i < m.getParameters().size(); i++) {
            if (m.getParameter(i).getNameAsString().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    // ---------- rendering -------------------------------------------------------------------------

    private Estimate render(MethodDeclaration m, Result r) {
        Symbols symbols = new Symbols(m, r.preferred);
        List<Cost> all = new ArrayList<>();
        r.recurrenceArgs.forEach(all::add);
        r.steps.forEach(s -> all.addAll(s.costs()));
        if (r.time != null) {
            all.add(r.time);
        }
        all.add(r.space);
        all.forEach(c -> c.symbols().forEach(symbols::letter));
        List<String> steps = new ArrayList<>();
        for (Step s : r.steps) {
            String text = s.text();
            for (int i = 0; i < s.costs().size(); i++) {
                Cost c = s.costs().get(i);
                String shown = c.render(symbols::letter);
                text = text.replace("{" + i + "}", shown.substring(2, shown.length() - 1));
            }
            steps.add("  ".repeat(s.depth()) + text);
        }
        String time = r.time == null ? null : r.timeText != null ? r.timeText.replace("n", firstLetter(r, symbols))
                : r.time.render(symbols::letter);
        if (r.time != null && r.recurrence == null && !r.time.isConstant()) {
            steps.add("Loops inside loops multiply and statements one after another add; keeping the largest "
                    + "term gives " + time + ".");
        }
        String recurrence = r.recurrence == null ? null : r.recurrence;
        if (recurrence != null) {
            Cost n = r.recurrenceArgs.isEmpty() ? null : r.recurrenceArgs.get(0);
            Cost f = r.recurrenceArgs.size() > 1 ? r.recurrenceArgs.get(1) : null;
            if (n != null) {
                String nn = n.render(symbols::letter);
                recurrence = recurrence.replace("{0}", nn.substring(2, nn.length() - 1));
            }
            if (f != null) {
                String ff = f.render(symbols::letter);
                recurrence = recurrence.replace("{1}", ff.substring(2, ff.length() - 1));
            }
        }
        String space = r.space == null || r.time == null ? null : r.space.render(symbols::letter);
        return new Estimate(m.getNameAsString(), line(m), time, space, recurrence, r.rule, steps, symbols.legend());
    }

    private static String firstLetter(Result r, Symbols symbols) {
        String k = r.time.singleSymbol();
        return k == null ? "n" : symbols.letter(k);
    }

    /** Letters for size symbols, and what each stands for. */
    private static final class Symbols {
        private final Map<String, String> letters = new LinkedHashMap<>();
        private final Set<String> used = new LinkedHashSet<>();
        private final List<String> pool = new ArrayList<>(List.of("n", "m", "k", "p", "q", "r", "s", "t"));

        private final Map<String, String> preferred;

        Symbols(MethodDeclaration m, Map<String, String> preferred) {
            this.preferred = preferred;
            // names the method uses for other things can't be letters
            m.findAll(NameExpr.class).forEach(n -> used.add(n.getNameAsString()));
            m.getParameters().forEach(p -> used.add(p.getNameAsString()));
        }

        String letter(String key) {
            String l = letters.get(key);
            if (l != null) {
                return l;
            }
            String rest = key.substring(key.indexOf(':') + 1);
            String pref = preferred.get(key);
            if (pref != null && pref.length() <= 3 && !letters.containsValue(pref)) {
                l = pref; // int n = arr.length → n
            } else if (key.startsWith("var:") && rest.length() <= 3 && rest.matches("[A-Za-z]\\w*")) {
                l = rest; // n, m, N, V, E, len...
            } else {
                List<String> order = new ArrayList<>(pool);
                if (key.startsWith("loop:") || key.startsWith("items:")) {
                    order.add(0, "k");
                }
                l = order.stream().filter(x -> !letters.containsValue(x) && (!used.contains(x) || x.equals(rest)))
                        .findFirst().orElse("x" + letters.size());
            }
            letters.put(key, l);
            return l;
        }

        List<String> legend() {
            List<String> out = new ArrayList<>();
            letters.forEach((key, l) -> {
                String kind = key.substring(0, key.indexOf(':'));
                String rest = key.substring(key.indexOf(':') + 1);
                String what = switch (kind) {
                    case "size" -> rest + ".size()";
                    case "chars" -> rest + ".length()";
                    case "len" -> rest.endsWith("[]") ? "the length of each " + rest.substring(0, rest.length() - 2)
                            + "[i] (row or neighbour list)" : rest + (rest.contains("(") ? "" : ".length");
                    case "range" -> {
                        String[] lohi = rest.split(":");
                        yield lohi[1] + " − " + lohi[0] + " + 1, the size of the range";
                    }
                    case "loop" -> "the number of iterations of the loop on line " + rest;
                    case "nodes" -> "the number of nodes reachable from " + rest;
                    case "items" -> "the number of items that go through " + rest;
                    case "value" -> "the largest value in " + rest;
                    case "expr" -> "the value of " + rest;
                    case "var" -> l.equals(rest) ? null : "the value of " + rest;
                    default -> null;
                };
                if (what != null) {
                    out.add(l + " = " + what);
                }
            });
            return out;
        }
    }
}
