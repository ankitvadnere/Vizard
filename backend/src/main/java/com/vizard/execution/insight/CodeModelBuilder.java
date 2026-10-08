package com.vizard.execution.insight;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.vizard.execution.insight.CodeModel.ArrayAccessSite;
import com.vizard.execution.insight.CodeModel.ConditionSite;
import com.vizard.execution.insight.CodeModel.LoopSite;
import com.vizard.execution.insight.CodeModel.MethodSite;
import com.vizard.execution.insight.CodeModel.PointerSpec;
import com.vizard.execution.insight.CodeModel.RangeSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Extracts the {@link CodeModel} from the JavaParser AST: where conditions, loops and array
 * accesses are, and which int variables index which arrays. Pure static analysis; nothing runs.
 */
public final class CodeModelBuilder {

    private CodeModelBuilder() {
    }

    public static CodeModel build(CompilationUnit cu) {
        Map<Integer, List<ConditionSite>> conditions = new TreeMap<>();
        List<LoopSite> loops = new ArrayList<>();
        Set<Expression> conditionRoots = Collections.newSetFromMap(new IdentityHashMap<>());

        for (IfStmt s : cu.findAll(IfStmt.class)) {
            Expression c = s.getCondition();
            conditionRoots.add(c);
            add(conditions, new ConditionSite("if", line(c), convert(c), bodyStart(s.getThenStmt()), bodyEnd(s.getThenStmt())));
        }
        for (WhileStmt s : cu.findAll(WhileStmt.class)) {
            Expression c = s.getCondition();
            conditionRoots.add(c);
            add(conditions, new ConditionSite("while", line(c), convert(c), bodyStart(s.getBody()), bodyEnd(s.getBody())));
            loops.add(new LoopSite("while", line(s), line(c), bodyStart(s.getBody()), bodyEnd(s.getBody()), endLine(s), null));
        }
        for (ForStmt s : cu.findAll(ForStmt.class)) {
            // Execution stops on the header line (where init/update are), so that's where the condition is shown.
            s.getCompare().ifPresent(c -> {
                conditionRoots.add(c);
                add(conditions, new ConditionSite("for", line(s), convert(c), bodyStart(s.getBody()), bodyEnd(s.getBody())));
            });
            loops.add(new LoopSite("for", line(s), line(s), bodyStart(s.getBody()), bodyEnd(s.getBody()), endLine(s),
                    forVariable(s)));
        }
        for (ForEachStmt s : cu.findAll(ForEachStmt.class)) {
            loops.add(new LoopSite("for-each", line(s), line(s), bodyStart(s.getBody()), bodyEnd(s.getBody()), endLine(s),
                    s.getVariableDeclarator().getNameAsString()));
        }
        for (DoStmt s : cu.findAll(DoStmt.class)) {
            Expression c = s.getCondition();
            conditionRoots.add(c);
            add(conditions, new ConditionSite("do", line(c), convert(c), bodyStart(s.getBody()), bodyEnd(s.getBody())));
            loops.add(new LoopSite("do", line(s), line(c), bodyStart(s.getBody()), bodyEnd(s.getBody()), endLine(s), null));
        }

        Map<Integer, List<ArrayAccessSite>> accesses = new TreeMap<>();
        for (ArrayAccessExpr a : cu.findAll(ArrayAccessExpr.class)) {
            add(accesses, line(a), new ArrayAccessSite(line(a), convert(a.getName()), convert(a.getIndex()),
                    text(a), isWrite(a), isInside(a, conditionRoots)));
        }

        List<MethodSite> methods = new ArrayList<>();
        for (CallableDeclaration<?> m : cu.findAll(CallableDeclaration.class)) {
            String name = m.isConstructorDeclaration() ? "<init>" : m.getNameAsString();
            List<PointerSpec> pointers = pointers(m);
            methods.add(new MethodSite(name, line(m), endLine(m), pointers, ranges(m, pointers)));
        }

        return new CodeModel(conditions, loops, accesses, methods);
    }

    // ---------- pointer variables -------------------------------------------------------

    /**
     * Int variables that index arrays in this method:
     * names used inside arr[...]; names they are computed from (mid = low + (high - low) / 2 adds
     * low and high); and loop variables compared with arr.length.
     */
    private static List<PointerSpec> pointers(CallableDeclaration<?> method) {
        Map<String, Expression> arrays = new LinkedHashMap<>();
        Map<String, Set<String>> indexNames = new LinkedHashMap<>();

        for (ArrayAccessExpr a : method.findAll(ArrayAccessExpr.class)) {
            Expression array = a.getName();
            if (!(array.isNameExpr() || array.isFieldAccessExpr())) {
                continue; // e.g. grid[i][j]: the inner access is handled on its own
            }
            String key = text(array);
            arrays.putIfAbsent(key, array);
            indexNames.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(names(a.getIndex()));
        }

        for (ForStmt loop : method.findAll(ForStmt.class)) {
            loop.getCompare().ifPresent(compare -> {
                for (FieldAccessExpr length : compare.findAll(FieldAccessExpr.class)) {
                    if (length.getNameAsString().equals("length")) {
                        String key = text(length.getScope());
                        if (arrays.containsKey(key)) {
                            indexNames.get(key).addAll(names(compare));
                        }
                    }
                }
            });
        }

        // One round of propagation: names used to compute an index variable are pointers too.
        for (Set<String> pointerNames : indexNames.values()) {
            Set<String> derived = new LinkedHashSet<>();
            for (AssignExpr assign : method.findAll(AssignExpr.class)) {
                if (assign.getTarget().isNameExpr() && pointerNames.contains(assign.getTarget().asNameExpr().getNameAsString())) {
                    derived.addAll(names(assign.getValue()));
                }
            }
            for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
                if (pointerNames.contains(v.getNameAsString())) {
                    v.getInitializer().ifPresent(init -> derived.addAll(names(init)));
                }
            }
            pointerNames.addAll(derived);
        }

        List<PointerSpec> specs = new ArrayList<>();
        for (Map.Entry<String, Expression> e : arrays.entrySet()) {
            List<String> variables = new ArrayList<>(indexNames.get(e.getKey()));
            variables.removeAll(arrays.keySet()); // the arrays themselves are not pointers
            variables.removeIf(n -> n.equals("length"));
            if (!variables.isEmpty()) {
                specs.add(new PointerSpec(convert(e.getValue()), variables));
            }
        }
        return specs;
    }

    // ---------- active ranges -----------------------------------------------------------

    /**
     * The part of an array a method works on, recognised in two shapes:
     * <ul>
     *   <li>a loop that halves a range: {@code while (low <= high)} whose body computes a midpoint
     *       from low and high (binary search, iterative)</li>
     *   <li>a divide-and-conquer guard on two int parameters: {@code if (left < right)} (one
     *       recursive call of merge sort, quick sort, recursive binary search)</li>
     * </ul>
     * Other comparisons, such as {@code while (i <= mid && j <= right)} in a merge, are scans, not ranges.
     */
    private static List<RangeSpec> ranges(CallableDeclaration<?> method, List<PointerSpec> pointers) {
        List<Parameter> arrayParams = method.getParameters().stream()
                .filter(p -> p.getType().isArrayType()).toList();
        List<String> intParams = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            if (p.getType().asString().equals("int")) {
                intParams.add(p.getNameAsString());
            }
        }

        Map<String, RangeSpec> found = new LinkedHashMap<>();
        for (WhileStmt loop : method.findAll(WhileStmt.class)) {
            // "while (low <= high)" or "while (high >= low)": both mean the range is not empty.
            rangeBounds(loop.getCondition(), true).ifPresent(names -> {
                if (computesMidpoint(loop.getBody(), names[0], names[1])) {
                    Expr array = arrayFor(names, pointers, arrayParams);
                    if (array != null) {
                        found.putIfAbsent(names[0] + "|" + names[1], new RangeSpec(array, names[0], names[1]));
                    }
                }
            });
        }
        for (IfStmt guard : method.findAll(IfStmt.class)) {
            // "if (lo < hi)" or "if (lo > hi) return -1": the start is whichever parameter comes first.
            rangeBounds(guard.getCondition(), false).ifPresent(pair -> {
                if (intParams.contains(pair[0]) && intParams.contains(pair[1])) {
                    String[] names = intParams.indexOf(pair[0]) < intParams.indexOf(pair[1])
                            ? pair : new String[]{pair[1], pair[0]};
                    Expr array = arrayFor(names, pointers, arrayParams);
                    if (array != null) {
                        found.putIfAbsent(names[0] + "|" + names[1], new RangeSpec(array, names[0], names[1]));
                    }
                }
            });
        }
        return new ArrayList<>(found.values());
    }

    /**
     * The whole condition compares two plain names. Returns {start, end}: for a less-than test
     * that's {left, right}; for greater-than it's flipped when the test means "not empty".
     */
    private static Optional<String[]> rangeBounds(Expression condition, boolean meansNotEmpty) {
        Expression c = condition;
        while (c.isEnclosedExpr()) {
            c = c.asEnclosedExpr().getInner();
        }
        if (!c.isBinaryExpr()) {
            return Optional.empty();
        }
        BinaryExpr b = c.asBinaryExpr();
        BinaryExpr.Operator op = b.getOperator();
        boolean less = op == BinaryExpr.Operator.LESS || op == BinaryExpr.Operator.LESS_EQUALS;
        boolean greater = op == BinaryExpr.Operator.GREATER || op == BinaryExpr.Operator.GREATER_EQUALS;
        if (!(less || greater) || !b.getLeft().isNameExpr() || !b.getRight().isNameExpr()) {
            return Optional.empty();
        }
        String left = b.getLeft().asNameExpr().getNameAsString();
        String right = b.getRight().asNameExpr().getNameAsString();
        return Optional.of(greater && meansNotEmpty ? new String[]{right, left} : new String[]{left, right});
    }

    /** Somewhere in the body: x = (lo + hi) / 2, lo + (hi - lo) / 2, (lo + hi) >>> 1 ... */
    private static boolean computesMidpoint(Node body, String lo, String hi) {
        List<Expression> values = new ArrayList<>();
        body.findAll(VariableDeclarator.class).forEach(v -> v.getInitializer().ifPresent(values::add));
        body.findAll(AssignExpr.class).forEach(a -> values.add(a.getValue()));
        for (Expression value : values) {
            Set<String> used = names(value);
            boolean halves = value.findAll(BinaryExpr.class).stream().anyMatch(b ->
                    (b.getOperator() == BinaryExpr.Operator.DIVIDE && b.getRight().toString().equals("2"))
                            || ((b.getOperator() == BinaryExpr.Operator.SIGNED_RIGHT_SHIFT
                            || b.getOperator() == BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT)
                            && b.getRight().toString().equals("1")));
            if (halves && used.contains(lo) && used.contains(hi)) {
                return true;
            }
        }
        return false;
    }

    /** The array both names index, or else the method's only array parameter. */
    private static Expr arrayFor(String[] names, List<PointerSpec> pointers, List<Parameter> arrayParams) {
        for (PointerSpec spec : pointers) {
            if (spec.variables().contains(names[0]) && spec.variables().contains(names[1])) {
                return spec.array();
            }
        }
        return arrayParams.size() == 1 ? new Expr.Name(arrayParams.get(0).getNameAsString()) : null;
    }

    private static Set<String> names(Expression e) {
        Set<String> result = new LinkedHashSet<>();
        if (e.isNameExpr()) {
            result.add(e.asNameExpr().getNameAsString());
        }
        for (NameExpr n : e.findAll(NameExpr.class)) {
            result.add(n.getNameAsString());
        }
        return result;
    }

    private static String forVariable(ForStmt loop) {
        for (Expression init : loop.getInitialization()) {
            if (init instanceof VariableDeclarationExpr decl && !decl.getVariables().isEmpty()) {
                return decl.getVariable(0).getNameAsString();
            }
            if (init instanceof AssignExpr assign && assign.getTarget().isNameExpr()) {
                return assign.getTarget().asNameExpr().getNameAsString();
            }
        }
        return null;
    }

    // ---------- expressions -------------------------------------------------------------

    static Expr convert(Expression e) {
        String text = text(e);
        if (e.isIntegerLiteralExpr()) return new Expr.Literal(e.asIntegerLiteralExpr().asNumber().longValue(), text);
        if (e.isLongLiteralExpr()) return new Expr.Literal(e.asLongLiteralExpr().asNumber().longValue(), text);
        if (e.isDoubleLiteralExpr()) return new Expr.Literal(e.asDoubleLiteralExpr().asDouble(), text);
        if (e.isCharLiteralExpr()) return new Expr.Literal(e.asCharLiteralExpr().asChar(), text);
        if (e.isBooleanLiteralExpr()) return new Expr.Literal(e.asBooleanLiteralExpr().getValue(), text);
        if (e.isStringLiteralExpr()) return new Expr.Literal(e.asStringLiteralExpr().asString(), text);
        if (e.isNullLiteralExpr()) return new Expr.Literal(ExpressionEvaluator.NULL, text);
        if (e.isNameExpr()) return new Expr.Name(e.asNameExpr().getNameAsString());
        if (e.isThisExpr()) return new Expr.Name("this");
        if (e.isEnclosedExpr()) return new Expr.Paren(convert(((EnclosedExpr) e).getInner()), text);
        if (e.isArrayAccessExpr()) {
            ArrayAccessExpr a = e.asArrayAccessExpr();
            return new Expr.ArrayAccess(convert(a.getName()), convert(a.getIndex()), text);
        }
        if (e.isFieldAccessExpr()) {
            FieldAccessExpr f = e.asFieldAccessExpr();
            return new Expr.FieldAccess(convert(f.getScope()), f.getNameAsString(), text);
        }
        if (e.isBinaryExpr()) {
            BinaryExpr b = e.asBinaryExpr();
            return new Expr.Binary(b.getOperator().asString(), convert(b.getLeft()), convert(b.getRight()), text);
        }
        if (e.isUnaryExpr()) {
            UnaryExpr u = e.asUnaryExpr();
            boolean changesVariable = u.getOperator().isPrefix() && u.getOperator() != UnaryExpr.Operator.PLUS
                    && u.getOperator() != UnaryExpr.Operator.MINUS
                    && u.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
                    && u.getOperator() != UnaryExpr.Operator.BITWISE_COMPLEMENT
                    || u.getOperator().isPostfix();
            return changesVariable
                    ? new Expr.Opaque(text)
                    : new Expr.Unary(u.getOperator().asString(), convert(u.getExpression()), text);
        }
        if (e.isConditionalExpr()) {
            ConditionalExpr c = e.asConditionalExpr();
            return new Expr.Conditional(convert(c.getCondition()), convert(c.getThenExpr()), convert(c.getElseExpr()), text);
        }
        if (e.isCastExpr()) {
            CastExpr c = e.asCastExpr();
            return new Expr.Cast(c.getType().asString(), convert(c.getExpression()), text);
        }
        return new Expr.Opaque(text); // method calls, assignments, object creation, lambdas...
    }

    // ---------- helpers -----------------------------------------------------------------

    private static boolean isWrite(ArrayAccessExpr a) {
        Optional<Node> parent = a.getParentNode();
        if (parent.isEmpty()) {
            return false;
        }
        if (parent.get() instanceof AssignExpr assign) {
            return assign.getTarget() == a;
        }
        if (parent.get() instanceof UnaryExpr unary) {
            return unary.getOperator().isPostfix() || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
                    || unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT;
        }
        return false;
    }

    private static boolean isInside(Node node, Set<Expression> roots) {
        Node current = node;
        while (current != null && !(current instanceof Statement)) {
            if (current instanceof Expression e && roots.contains(e)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * First line of a body's <i>statements</i>. For "for (...) {" the brace sits on the header
     * line, but the code that runs per iteration starts on the next line.
     */
    private static int bodyStart(Statement body) {
        if (body instanceof BlockStmt block && !block.getStatements().isEmpty()) {
            return line(block.getStatement(0));
        }
        return line(body);
    }

    private static int bodyEnd(Statement body) {
        if (body instanceof BlockStmt block && !block.getStatements().isEmpty()) {
            return endLine(block.getStatement(block.getStatements().size() - 1));
        }
        return endLine(body);
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static int endLine(Node n) {
        return n.getEnd().map(p -> p.line).orElse(0);
    }

    /** The expression as the user wrote it (original tokens), on one line. */
    private static String text(Node n) {
        String raw = n.getTokenRange().map(Object::toString).orElseGet(n::toString);
        return raw.replaceAll("\\s+", " ").strip();
    }

    private static <T> void add(Map<Integer, List<T>> map, int line, T item) {
        map.computeIfAbsent(line, k -> new ArrayList<>()).add(item);
    }

    private static void add(Map<Integer, List<ConditionSite>> map, ConditionSite site) {
        add(map, site.line(), site);
    }
}
