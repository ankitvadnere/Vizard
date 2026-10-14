package com.vizard.execution.insight;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.vizard.execution.insight.CodeModel.RecursionSpec;
import com.vizard.execution.insight.CodeModel.TableSite;
import com.vizard.execution.insight.CodeModel.TableSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds dynamic programming in the AST, in its two forms, plus the plain recursion DP improves on.
 *
 * <ul>
 *   <li><b>Tabulation</b> (bottom-up): an array created in the method and filled inside loops from
 *       cells of the same array computed earlier: {@code dp[i] = dp[i - 1] + dp[i - 2]},
 *       {@code dp[i][w] = Math.max(dp[i - 1][w], dp[i - 1][w - wt[i - 1]] + val[i - 1])}.
 *       Reading the same array is the recurrence; that is what separates a DP table from an array
 *       that is merely written (or an insertion sort, whose array is not created in the method).</li>
 *   <li><b>Memoisation</b> (top-down): a recursive method that checks a memo before computing
 *       ({@code if (memo[n] != -1) return memo[n];}) and stores the result after.</li>
 *   <li><b>Plain overlapping recursion</b>: two or more recursive calls whose arguments are the
 *       parameters plus or minus constants ({@code fib(n - 1) + fib(n - 2)}), so the same calls repeat.</li>
 * </ul>
 */
public final class DpModelBuilder {

    private static final Pattern MINUS_ELEMENT = Pattern.compile("\\w+-\\w+\\[[^\\]]+\\]");

    private DpModelBuilder() {
    }

    /** A memo check and the table it reads. */
    public record Memo(String name, IfStmt check, boolean array) {
    }

    public static List<TableSpec> tables(CompilationUnit cu) {
        List<TableSpec> specs = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            specs.addAll(tabulation(m));
            memoTable(m).ifPresent(specs::add);
        }
        return specs;
    }

    public static List<RecursionSpec> recursions(CompilationUnit cu) {
        List<RecursionSpec> specs = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            Optional<Memo> memo = memo(m);
            if (memo.isPresent()) {
                specs.add(new RecursionSpec(m.getNameAsString(), line(m), endLine(m), memo.get().name(),
                        line(memo.get().check()), endLine(memo.get().check()), classify(m, null, 0)));
            } else if (isPlainOverlappingRecursion(m, cu)) {
                specs.add(new RecursionSpec(m.getNameAsString(), line(m), endLine(m), null, 0, 0, classify(m, null, 0)));
            }
        }
        return specs;
    }

    // ---------- memoisation ---------------------------------------------------------------

    /**
     * {@code if (memo[n] != -1) return memo[n];} (or memo.containsKey(n) / memo.get(n)) in a recursive
     * method that also stores into the same memo.
     */
    public static Optional<Memo> memo(MethodDeclaration m) {
        if (m.getBody().isEmpty() || selfCalls(m).isEmpty()) {
            return Optional.empty();
        }
        for (IfStmt check : m.findAll(IfStmt.class)) {
            String name = memoRead(check.getCondition());
            if (name == null) {
                continue;
            }
            boolean returnsIt = check.getThenStmt().findAll(ReturnStmt.class).stream()
                    .anyMatch(r -> r.getExpression().map(e -> text(e).startsWith(name + "[")
                            || text(e).startsWith(name + ".get(")).orElse(false));
            boolean stores = m.findAll(AssignExpr.class).stream()
                    .anyMatch(a -> baseName(a.getTarget()).equals(name) && a.getTarget().isArrayAccessExpr())
                    || m.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals("put")
                    && c.getScope().map(s -> text(s).equals(name)).orElse(false));
            if (returnsIt && stores) {
                boolean array = check.getCondition().findFirst(ArrayAccessExpr.class).isPresent();
                return Optional.of(new Memo(name, check, array));
            }
        }
        return Optional.empty();
    }

    private static String memoRead(Expression condition) {
        for (ArrayAccessExpr a : condition.findAll(ArrayAccessExpr.class)) {
            if (!(a.getParentNode().orElse(null) instanceof ArrayAccessExpr)) {
                return baseName(a);
            }
        }
        for (MethodCallExpr c : condition.findAll(MethodCallExpr.class)) {
            if ((c.getNameAsString().equals("containsKey") || c.getNameAsString().equals("get"))
                    && c.getScope().isPresent() && c.getScope().get().isNameExpr()) {
                return c.getScope().get().toString();
            }
        }
        return null;
    }

    private static Optional<TableSpec> memoTable(MethodDeclaration m) {
        Optional<Memo> found = memo(m);
        if (found.isEmpty() || !found.get().array()) {
            return Optional.empty();
        }
        String name = found.get().name();
        List<TableSite> sites = new ArrayList<>();
        ArrayAccessExpr checked = found.get().check().getCondition().findFirst(ArrayAccessExpr.class,
                a -> !(a.getParentNode().orElse(null) instanceof ArrayAccessExpr)).orElse(null);
        Expression firstTarget = null;
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getTarget().isArrayAccessExpr() && baseName(a.getTarget()).equals(name)) {
                firstTarget = firstTarget == null ? a.getTarget() : firstTarget;
                sites.add(new TableSite(line(a), CodeModelBuilder.convert(a.getTarget()), true, text(a.getTarget()),
                        pretty(a.getValue()), List.of(), otherArrays(a.getValue(), name), names(a.getValue(), name)));
            }
        }
        if (checked != null) {
            sites.add(0, new TableSite(line(found.get().check().getCondition()), CodeModelBuilder.convert(checked),
                    false, "check", pretty(found.get().check().getCondition()),
                    List.of(CodeModelBuilder.convert(checked)), List.of(), List.of()));
        }
        int dims = firstTarget instanceof ArrayAccessExpr t && t.getName().isArrayAccessExpr() ? 2 : 1;
        String[] vars = indexVars(firstTarget);
        return Optional.of(new TableSpec(m.getNameAsString(), line(m), endLine(m), "memo", classify(m, null, dims), name,
                dims, sites, vars[0], vars[1], null, 0, null, 0, 0));
    }

    // ---------- tabulation ----------------------------------------------------------------

    /** The tables one method fills: bottom-up tables it creates, and the memo it uses. */
    public static List<TableSpec> tablesOf(MethodDeclaration m) {
        List<TableSpec> all = new ArrayList<>(tabulation(m));
        memoTable(m).ifPresent(all::add);
        return all;
    }

    private static List<TableSpec> tabulation(MethodDeclaration m) {
        List<TableSpec> specs = new ArrayList<>();
        if (m.getBody().isEmpty()) {
            return specs;
        }
        Set<String> graphState = new LinkedHashSet<>();
        for (CodeModel.GraphSpec g : GraphModelBuilder.find(m, m.findCompilationUnit().orElse(null))) {
            graphState.add(g.dist());
            graphState.add(g.visited());
            graphState.add(g.parent());
        }
        for (String table : allocatedArrays(m)) {
            if (graphState.contains(table)) {
                continue; // dist[] of BFS or Dijkstra: a graph algorithm's state, drawn on the graph
            }
            List<AssignExpr> writes = m.findAll(AssignExpr.class).stream()
                    .filter(a -> a.getTarget().isArrayAccessExpr() && baseName(a.getTarget()).equals(table))
                    .toList();
            Set<String> readers = localsReading(m, table);
            List<AssignExpr> recurrences = writes.stream()
                    .filter(a -> loopDepth(a, m) > 0 && (readsTable(a.getValue(), table, readers)
                            || a.getOperator() != AssignExpr.Operator.ASSIGN))
                    .toList();
            if (recurrences.isEmpty()) {
                continue;
            }
            AssignExpr main = recurrences.stream().max(Comparator.comparingInt(a -> loopDepth(a, m))).get();
            int dims = main.getTarget().asArrayAccessExpr().getName().isArrayAccessExpr() ? 2 : 1;
            List<ArrayAccessExpr> reads = tableReads(m, table);
            if (dims == 1 && !looksLikeDp(reads, main, m)) {
                continue; // a running total such as prefix[i] = prefix[i - 1] + a[i] is not a DP table
            }

            List<TableSite> sites = new ArrayList<>();
            Set<Integer> lines = new LinkedHashSet<>();
            for (AssignExpr a : writes) {
                if (lines.add(line(a))) {
                    List<Expr> r = new ArrayList<>();
                    if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
                        r.add(CodeModelBuilder.convert(a.getTarget()));
                    }
                    r.addAll(readsIn(a.getValue(), table));
                    String shown = a.getOperator() == AssignExpr.Operator.ASSIGN ? pretty(a.getValue())
                            : pretty(a.getTarget()) + " " + a.getOperator().asString().replace("=", "") + " " + pretty(a.getValue());
                    sites.add(new TableSite(line(a), CodeModelBuilder.convert(a.getTarget()), true, text(a.getTarget()),
                            shown, r, otherArrays(a.getValue(), table), names(a.getValue(), table)));
                }
            }
            Node nest = outermostLoop(main, m);
            if (nest != null) {
                for (VariableDeclarator d : nest.findAll(VariableDeclarator.class)) {
                    if (d.getInitializer().isPresent() && !readsIn(d.getInitializer().get(), table).isEmpty()
                            && lines.add(line(d))) {
                        sites.add(new TableSite(line(d), CodeModelBuilder.convert(main.getTarget()), false,
                                d.getNameAsString(), pretty(d.getInitializer().get()),
                                readsIn(d.getInitializer().get(), table), otherArrays(d.getInitializer().get(), table),
                                names(d.getInitializer().get(), table)));
                    }
                }
                for (IfStmt test : nest.findAll(IfStmt.class)) {
                    if (!readsIn(test.getCondition(), table).isEmpty() && lines.add(line(test.getCondition()))) {
                        sites.add(new TableSite(line(test.getCondition()), CodeModelBuilder.convert(main.getTarget()), false,
                                "check", pretty(test.getCondition()), readsIn(test.getCondition(), table),
                                otherArrays(test.getCondition(), table), names(test.getCondition(), table)));
                    }
                }
            }
            String[] vars = indexVars(main.getTarget());
            String[] rowString = labelString(m, vars[0]);
            String[] colString = labelString(m, vars[1]);
            specs.add(new TableSpec(m.getNameAsString(), line(m), endLine(m), "tabulation",
                    classify(m, table, dims), table, dims, sites, vars[0], vars[1],
                    rowString == null ? null : rowString[0], rowString == null ? 0 : Integer.parseInt(rowString[1]),
                    colString == null ? null : colString[0], colString == null ? 0 : Integer.parseInt(colString[1]),
                    loopDepth(main, m)));
        }
        return specs;
    }

    /** 1-D: two different earlier cells (Fibonacci), a cell at a computed distance (coin change), or nested loops. */
    private static boolean looksLikeDp(List<ArrayAccessExpr> reads, AssignExpr main, MethodDeclaration m) {
        Set<String> distinct = new LinkedHashSet<>();
        boolean computedOffset = false;
        for (ArrayAccessExpr r : reads) {
            distinct.add(text(r.getIndex()));
            computedOffset |= MINUS_ELEMENT.matcher(text(r.getIndex())).find();
        }
        return distinct.size() >= 2 || computedOffset || loopDepth(main, m) >= 2;
    }

    private static Set<String> allocatedArrays(MethodDeclaration m) {
        Set<String> names = new LinkedHashSet<>();
        for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
            if (d.getInitializer().isPresent() && d.getInitializer().get() instanceof ArrayCreationExpr) {
                names.add(d.getNameAsString());
            }
        }
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getValue() instanceof ArrayCreationExpr && a.getTarget().isNameExpr()) {
                names.add(a.getTarget().toString());
            }
        }
        return names;
    }

    /** Locals computed from the table: {@code int cost = dp[i][k] + ...}. */
    private static Set<String> localsReading(MethodDeclaration m, String table) {
        Set<String> names = new LinkedHashSet<>();
        for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
            if (d.getInitializer().isPresent() && !readsIn(d.getInitializer().get(), table).isEmpty()) {
                names.add(d.getNameAsString());
            }
        }
        return names;
    }

    private static boolean readsTable(Expression value, String table, Set<String> readers) {
        if (!readsIn(value, table).isEmpty()) {
            return true;
        }
        return value.findAll(NameExpr.class).stream().anyMatch(n -> readers.contains(n.getNameAsString()))
                || value.isNameExpr() && readers.contains(value.toString());
    }

    private static List<ArrayAccessExpr> tableReads(MethodDeclaration m, String table) {
        List<ArrayAccessExpr> reads = new ArrayList<>();
        for (ArrayAccessExpr a : m.findAll(ArrayAccessExpr.class)) {
            boolean outer = !(a.getParentNode().orElse(null) instanceof ArrayAccessExpr p && p.getName() == a);
            boolean written = a.getParentNode().orElse(null) instanceof AssignExpr asg && asg.getTarget() == a
                    && asg.getOperator() == AssignExpr.Operator.ASSIGN;
            if (outer && !written && baseName(a).equals(table)) {
                reads.add(a);
            }
        }
        return reads;
    }

    /** The table cells an expression reads (whole accesses: dp[i - 1][w], not the inner dp[i - 1]). */
    private static List<Expr> readsIn(Expression e, String table) {
        List<Expr> result = new ArrayList<>();
        List<ArrayAccessExpr> all = new ArrayList<>(e.findAll(ArrayAccessExpr.class));
        if (e instanceof ArrayAccessExpr self && !all.contains(self)) {
            all.add(0, self);
        }
        for (ArrayAccessExpr a : all) {
            boolean outer = !(a.getParentNode().orElse(null) instanceof ArrayAccessExpr p && p.getName() == a);
            if (outer && baseName(a).equals(table) && !insideTableIndex(a, table)) {
                result.add(CodeModelBuilder.convert(a));
            }
        }
        return result;
    }

    private static boolean insideTableIndex(ArrayAccessExpr a, String table) {
        for (Node p = a.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof ArrayAccessExpr outer && baseName(outer).equals(table) && outer.getIndex().isAncestorOf(a)) {
                return true;
            }
        }
        return false;
    }

    /** Other arrays in the expression, to show with their values (val[i - 1], wt[i - 1], dims[k]). */
    private static List<Expr> otherArrays(Expression e, String table) {
        List<Expr> result = new ArrayList<>();
        for (ArrayAccessExpr a : e.findAll(ArrayAccessExpr.class)) {
            boolean outer = !(a.getParentNode().orElse(null) instanceof ArrayAccessExpr p && p.getName() == a);
            boolean inTableIndex = false;
            for (Node p = a.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
                if (p instanceof ArrayAccessExpr x && baseName(x).equals(table)) {
                    inTableIndex = true;
                }
            }
            if (outer && !inTableIndex && !baseName(a).equals(table)) {
                result.add(CodeModelBuilder.convert(a));
            }
        }
        return result;
    }

    /** Plain variables of the expression outside any array index (cost, a, j), to show with their values. */
    private static List<String> names(Expression e, String table) {
        Set<String> result = new LinkedHashSet<>();
        List<NameExpr> all = new ArrayList<>(e.findAll(NameExpr.class));
        if (e instanceof NameExpr self && !all.contains(self)) {
            all.add(self);
        }
        for (NameExpr n : all) {
            boolean inIndex = false;
            boolean scope = false;
            for (Node p = n.getParentNode().orElse(null); p != null && p != e.getParentNode().orElse(null);
                 p = p.getParentNode().orElse(null)) {
                if (p instanceof ArrayAccessExpr) {
                    inIndex = true;
                }
            }
            if (n.getParentNode().orElse(null) instanceof MethodCallExpr c && c.getScope().map(s -> s == n).orElse(false)) {
                scope = true;
            }
            String name = n.getNameAsString();
            if (!inIndex && !scope && !name.equals(table) && !name.equals("Integer") && !name.equals("Math")
                    && !name.equals("Long")) {
                result.add(name);
            }
        }
        return new ArrayList<>(result);
    }

    /** {@code a.charAt(i - 1)} labels the rows indexed by i with the letters of a. Returns {a, offset}. */
    private static String[] labelString(MethodDeclaration m, String indexVar) {
        if (indexVar == null) {
            return null;
        }
        for (MethodCallExpr c : m.findAll(MethodCallExpr.class)) {
            if (c.getNameAsString().equals("charAt") && c.getArguments().size() == 1 && c.getScope().isPresent()
                    && c.getScope().get().isNameExpr()) {
                String arg = text(c.getArgument(0));
                if (arg.equals(indexVar)) {
                    return new String[]{c.getScope().get().toString(), "0"};
                }
                if (arg.equals(indexVar + "-1")) {
                    return new String[]{c.getScope().get().toString(), "1"};
                }
            }
        }
        return null;
    }

    // ---------- plain recursion -------------------------------------------------------------

    /**
     * Two or more recursive calls whose arguments are the parameters themselves or the parameters
     * plus or minus a constant ({@code fib(n - 1) + fib(n - 2)}, {@code C(n - 1, k - 1) + C(n - 1, k)}):
     * the same calls come up again and again. Calls in a loop (permutations, DFS) or with values
     * computed in the method (merge sort's mid) are not this shape; neither are backtracking searches.
     */
    public static boolean isPlainOverlappingRecursion(MethodDeclaration m, CompilationUnit cu) {
        List<MethodCallExpr> calls = selfCalls(m);
        if (calls.size() < 2 || memo(m).isPresent() || BacktrackModelBuilder.find(m, cu).isPresent()) {
            return false;
        }
        Set<String> params = new LinkedHashSet<>();
        for (Parameter p : m.getParameters()) {
            params.add(p.getNameAsString());
        }
        for (MethodCallExpr c : calls) {
            if (c.findAncestor(ForStmt.class).filter(m::isAncestorOf).isPresent()
                    || c.findAncestor(ForEachStmt.class).filter(m::isAncestorOf).isPresent()
                    || c.findAncestor(WhileStmt.class).filter(m::isAncestorOf).isPresent()) {
                return false;
            }
            for (Expression arg : c.getArguments()) {
                if (!simpleArgument(arg, params)) {
                    return false;
                }
            }
        }
        Set<String> distinct = new LinkedHashSet<>();
        calls.forEach(c -> distinct.add(text(c)));
        return distinct.size() >= 2;
    }

    private static boolean simpleArgument(Expression arg, Set<String> params) {
        Expression e = arg;
        while (e instanceof EnclosedExpr p) {
            e = p.getInner();
        }
        if (e.isNameExpr()) {
            return params.contains(e.toString());
        }
        if (e.isLiteralExpr()) {
            return true;
        }
        if (e instanceof BinaryExpr b && (b.getOperator() == BinaryExpr.Operator.MINUS
                || b.getOperator() == BinaryExpr.Operator.PLUS)) {
            return simpleArgument(b.getLeft(), params) && (b.getRight().isIntegerLiteralExpr()
                    || simpleArgument(b.getRight(), params));
        }
        return false;
    }

    // ---------- which problem -------------------------------------------------------------

    /**
     * Named by structure: Fibonacci adds the two previous values; knapsack maximises with a capacity
     * reduced by an item's weight; LCS and edit distance compare letters (max vs min); coin change
     * looks back by a coin's value; matrix chain splits [i..j] at k into dp[i][k] and dp[k + 1][j].
     */
    public static String classify(MethodDeclaration m, String table, int dims) {
        String body = m.getBody().map(DpModelBuilder::text).orElse("");
        String name = m.getNameAsString();
        boolean max = body.contains("Math.max");
        boolean min = body.contains("Math.min") || body.contains("<" + table + "[") || body.contains("cost<");
        boolean letters = body.contains(".charAt(");
        if (table == null) {
            // recursion: look at the recursive calls' arguments
            boolean nMinus1 = body.contains(name + "(n-1") || Pattern.compile(name + "\\((\\w+)-1\\)").matcher(body).find();
            boolean twoBack = Pattern.compile(name + "\\(([^()]*?)(\\w+)-1([^()]*)\\)\\+" + name + "\\(([^()]*?)(\\w+)-2").matcher(body).find();
            if (twoBack || nMinus1 && body.contains("-2")) {
                return "fibonacci";
            }
            if (letters) {
                return max ? "lcs" : min ? "edit-distance" : "dp";
            }
            if (MINUS_ELEMENT.matcher(body).find()) {
                return max ? "knapsack" : "coin-change";
            }
            return "dp";
        }
        String t = Pattern.quote(table);
        if (dims == 1 && body.contains(table + "[i-1]") && body.contains(table + "[i-2]")
                || dims == 1 && Pattern.compile(t + "\\[(\\w+)-1\\]\\+" + t + "\\[\\1-2\\]").matcher(body).find()) {
            return "fibonacci";
        }
        if (letters) {
            return max ? "lcs" : min ? "edit-distance" : "dp";
        }
        if (dims == 2 && Pattern.compile(t + "\\[(\\w+)\\]\\[(\\w+)\\]\\+" + t + "\\[\\2\\+1\\]\\[(\\w+)\\]").matcher(body).find()) {
            return "matrix-chain";
        }
        boolean minusElement = Pattern.compile(t + "(\\[[^\\]]*\\])?\\[\\w+-\\w+\\[").matcher(body).find();
        if (minusElement && dims == 2 && max) {
            return "knapsack";
        }
        if (minusElement && dims == 1) {
            return "coin-change";
        }
        return "dp";
    }

    // ---------- helpers -------------------------------------------------------------------

    private static List<MethodCallExpr> selfCalls(MethodDeclaration m) {
        return m.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(m.getNameAsString())
                        && (c.getScope().isEmpty() || c.getScope().get().isThisExpr())
                        && c.getArguments().size() == m.getParameters().size())
                .toList();
    }

    private static String[] indexVars(Expression target) {
        if (!(target instanceof ArrayAccessExpr a)) {
            return new String[]{null, null};
        }
        if (a.getName() instanceof ArrayAccessExpr row) {
            return new String[]{nameOrNull(row.getIndex()), nameOrNull(a.getIndex())};
        }
        return new String[]{null, nameOrNull(a.getIndex())};
    }

    private static String nameOrNull(Expression e) {
        return e.isNameExpr() ? e.toString() : null;
    }

    private static int loopDepth(Node n, MethodDeclaration m) {
        int depth = 0;
        for (Node p = n.getParentNode().orElse(null); p != null && p != m; p = p.getParentNode().orElse(null)) {
            if (p instanceof ForStmt || p instanceof WhileStmt || p instanceof ForEachStmt) {
                depth++;
            }
        }
        return depth;
    }

    private static Node outermostLoop(Node n, MethodDeclaration m) {
        Node found = null;
        for (Node p = n.getParentNode().orElse(null); p != null && p != m; p = p.getParentNode().orElse(null)) {
            if (p instanceof ForStmt || p instanceof WhileStmt || p instanceof ForEachStmt) {
                found = p;
            }
        }
        return found;
    }

    static String baseName(Expression e) {
        Expression x = e;
        while (x instanceof ArrayAccessExpr a) {
            x = a.getName();
        }
        return x.toString();
    }

    private static String pretty(Node n) {
        String raw = n.getTokenRange().map(Object::toString).orElseGet(n::toString);
        return raw.replaceAll("\\s+", " ").strip();
    }

    private static String text(Node n) {
        String raw = n.getTokenRange().map(Object::toString).orElseGet(n::toString);
        return raw.replaceAll("\\s+", "");
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static int endLine(Node n) {
        return n.getEnd().map(p -> p.line).orElse(0);
    }
}
