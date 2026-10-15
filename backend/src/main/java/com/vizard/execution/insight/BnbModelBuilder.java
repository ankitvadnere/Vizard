package com.vizard.execution.insight;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.vizard.execution.insight.CodeModel.BnbSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds branch and bound written with a live-node list:
 * <pre>
 *   PriorityQueue&lt;Node&gt; pq = ...;  pq.add(root);
 *   while (!pq.isEmpty()) {
 *       Node u = pq.poll();
 *       if (u.bound &lt;= best) continue;                  ← prune: can't beat the best
 *       Node v = new Node(u.level + 1, ...);              ← branch
 *       if (v.profit &gt; best) best = v.profit;          ← a better solution
 *       v.bound = bound(v);
 *       if (v.bound &gt; best) pq.add(v);                  ← keep it live only if it might win
 *   }
 * </pre>
 * The defining facts: the live list holds objects of a class of the program; new ones are made from
 * the one taken out; a field of them is compared with a variable that the loop also updates (the best
 * solution so far). A BFS or Dijkstra queue holds vertices and has no best to compare with.
 */
public final class BnbModelBuilder {

    private static final Set<String> TAKES = Set.of("poll", "remove", "pop", "removeFirst", "removeLast",
            "pollFirst", "pollLast");
    private static final Set<String> ADDS = Set.of("add", "offer", "push", "addLast", "addFirst", "offerLast",
            "offerFirst");

    private BnbModelBuilder() {
    }

    public static List<BnbSpec> build(CompilationUnit cu) {
        List<BnbSpec> specs = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            find(m, cu).ifPresent(specs::add);
        }
        return specs;
    }

    public static Optional<BnbSpec> find(MethodDeclaration m, CompilationUnit cu) {
        Set<String> classes = new LinkedHashSet<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> classes.add(c.getNameAsString()));
        for (WhileStmt loop : m.findAll(WhileStmt.class)) {
            for (VariableDeclarator d : loop.getBody().findAll(VariableDeclarator.class)) {
                if (d.getInitializer().isEmpty() || !(d.getInitializer().get() instanceof MethodCallExpr take)
                        || !TAKES.contains(take.getNameAsString()) || take.getScope().isEmpty()
                        || !classes.contains(d.getType().asString())) {
                    continue;
                }
                String frontier = take.getScope().get().toString();
                String nodeClass = d.getType().asString();
                String current = d.getNameAsString();
                Optional<BnbSpec> spec = analyse(m, cu, loop, frontier, nodeClass, current, take.getNameAsString());
                if (spec.isPresent()) {
                    return spec;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<BnbSpec> analyse(MethodDeclaration m, CompilationUnit cu, WhileStmt loop, String frontier,
                                             String nodeClass, String current, String takeName) {
        boolean branches = loop.getBody().findAll(ObjectCreationExpr.class).stream()
                .anyMatch(o -> o.getType().getNameAsString().equals(nodeClass));
        if (!branches) {
            return Optional.empty();
        }
        // Variables of the node class in this method (u, take, leave, v...).
        Set<String> nodeVars = new LinkedHashSet<>();
        for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
            if (d.getType().asString().equals(nodeClass)) {
                nodeVars.add(d.getNameAsString());
            }
        }
        Set<String> assigned = new LinkedHashSet<>();
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getTarget().isNameExpr()) {
                assigned.add(a.getTarget().toString());
            }
        }
        // bound field vs best: x.bound <= best, v.bound > best ...
        String boundField = null;
        String bestVar = null;
        for (BinaryExpr b : loop.getBody().findAll(BinaryExpr.class)) {
            if (!isOrdering(b.getOperator())) {
                continue;
            }
            FieldAccessExpr field = nodeField(b.getLeft(), nodeVars);
            Expression other = b.getRight();
            if (field == null) {
                field = nodeField(b.getRight(), nodeVars);
                other = b.getLeft();
            }
            if (field != null && other.isNameExpr() && assigned.contains(other.toString())
                    && !nodeVars.contains(other.toString())) {
                // the field also has to be assigned (v.bound = bound(v)) or set in the constructor: prefer a
                // field that is not the value field of the best update
                if (boundField == null || !isBestSource(loop, field.getNameAsString(), other.toString())) {
                    boundField = field.getNameAsString();
                    bestVar = other.toString();
                }
            }
        }
        if (boundField == null) {
            return Optional.empty();
        }
        String finalBest = bestVar;
        String valueField = null;
        // Direction from the bound test: "bound <= best → drop" means bigger is better; "bound >= best" smaller.
        boolean maximise = boundSaysMaximise(loop, boundField, bestVar, nodeVars);
        for (IfStmt test : loop.getBody().findAll(IfStmt.class)) {
            for (AssignExpr a : test.getThenStmt().findAll(AssignExpr.class)) {
                if (a.getTarget().isNameExpr() && a.getTarget().toString().equals(finalBest)) {
                    if (a.getValue() instanceof MethodCallExpr call && call.getScope().map(Object::toString)
                            .orElse("").equals("Math") && (call.getNameAsString().equals("min")
                            || call.getNameAsString().equals("max"))) {
                        maximise = call.getNameAsString().equals("max");
                        for (Expression arg : call.getArguments()) {
                            FieldAccessExpr f = nodeField(arg, nodeVars);
                            if (f != null) {
                                valueField = f.getNameAsString();
                            }
                        }
                        continue;
                    }
                    FieldAccessExpr source = nodeField(a.getValue(), nodeVars);
                    if (source != null) {
                        valueField = source.getNameAsString();
                        for (BinaryExpr b : test.getCondition().findAll(BinaryExpr.class)) {
                            FieldAccessExpr f = nodeField(b.getLeft(), nodeVars);
                            if (f != null && f.getNameAsString().equals(valueField) && isOrdering(b.getOperator())) {
                                maximise = b.getOperator() == BinaryExpr.Operator.GREATER
                                        || b.getOperator() == BinaryExpr.Operator.GREATER_EQUALS;
                            }
                        }
                    }
                }
            }
        }
        // level: u.level + 1
        String levelField = null;
        for (BinaryExpr b : loop.getBody().findAll(BinaryExpr.class)) {
            if (b.getOperator() == BinaryExpr.Operator.PLUS && b.getRight().toString().equals("1")) {
                FieldAccessExpr f = nodeField(b.getLeft(), Set.of(current));
                if (f != null) {
                    levelField = f.getNameAsString();
                    break;
                }
            }
        }
        // prune the current node: if (u.bound <= best ...) continue;
        List<Integer> pruneLines = new ArrayList<>();
        Map<Integer, String> addGuards = new LinkedHashMap<>();
        for (IfStmt test : loop.getBody().findAll(IfStmt.class)) {
            boolean leaves = test.getThenStmt() instanceof ContinueStmt
                    || !test.getThenStmt().findAll(ContinueStmt.class).isEmpty()
                    || !test.getThenStmt().findAll(ReturnStmt.class).isEmpty();
            boolean aboutCurrent = test.getCondition().findAll(FieldAccessExpr.class).stream()
                    .anyMatch(f -> f.getScope().toString().equals(current));
            if (leaves && aboutCurrent) {
                pruneLines.add(line(test.getCondition()));
            }
            for (MethodCallExpr add : test.getThenStmt().findAll(MethodCallExpr.class)) {
                if (ADDS.contains(add.getNameAsString()) && add.getScope().map(s -> s.toString().equals(frontier))
                        .orElse(false) && add.getArguments().size() == 1 && add.getArgument(0).isNameExpr()) {
                    addGuards.put(line(test.getCondition()), add.getArgument(0).toString());
                }
            }
        }
        String order = order(m, cu, frontier, takeName);
        String problem = fractionalBound(m, cu, boundField) ? "knapsack" : "branch-and-bound";
        return Optional.of(new BnbSpec(m.getNameAsString(), line(m), endLine(m), frontier, order, nodeClass, current,
                boundField, bestVar, valueField, levelField, maximise, problem, pruneLines, addGuards));
    }

    /** Is field f of a node the value assigned to the best (best = take.profit)? */
    /** true unless the bound test reads "drop when bound >= best" / "keep when bound < best" (minimising). */
    private static boolean boundSaysMaximise(WhileStmt loop, String boundField, String best, Set<String> nodeVars) {
        for (IfStmt test : loop.getBody().findAll(IfStmt.class)) {
            boolean drops = !test.getThenStmt().findAll(ContinueStmt.class).isEmpty();
            for (BinaryExpr b : test.getCondition().findAll(BinaryExpr.class)) {
                if (!isOrdering(b.getOperator()) || !b.getRight().toString().equals(best)) {
                    continue;
                }
                FieldAccessExpr f = nodeField(b.getLeft(), nodeVars);
                if (f == null || !f.getNameAsString().equals(boundField)) {
                    continue;
                }
                boolean less = b.getOperator() == BinaryExpr.Operator.LESS
                        || b.getOperator() == BinaryExpr.Operator.LESS_EQUALS;
                // drop when bound <= best → maximising; keep when bound > best → maximising
                return drops == less;
            }
        }
        return true;
    }

    private static boolean isBestSource(WhileStmt loop, String field, String best) {
        for (AssignExpr a : loop.getBody().findAll(AssignExpr.class)) {
            if (a.getTarget().toString().equals(best) && a.getValue() instanceof FieldAccessExpr f
                    && f.getNameAsString().equals(field)) {
                return true;
            }
        }
        return false;
    }

    private static String order(MethodDeclaration m, CompilationUnit cu, String frontier, String takeName) {
        for (VariableDeclarator d : cu.findAll(VariableDeclarator.class)) {
            if (d.getNameAsString().equals(frontier)) {
                String declared = d.getType().asString() + " " + d.getInitializer().map(Node::toString).orElse("");
                if (declared.contains("PriorityQueue")) {
                    return "priority";
                }
                if (declared.contains("Stack") || takeName.equals("pop") || takeName.equals("pollLast")
                        || takeName.equals("removeLast")) {
                    return "stack";
                }
            }
        }
        return takeName.equals("pop") ? "stack" : "queue";
    }

    /** The bound is computed by a helper that divides: the fractional-knapsack bound. */
    private static boolean fractionalBound(MethodDeclaration m, CompilationUnit cu, String boundField) {
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getTarget() instanceof FieldAccessExpr f && f.getNameAsString().equals(boundField)
                    && a.getValue() instanceof MethodCallExpr call) {
                for (MethodDeclaration h : cu.findAll(MethodDeclaration.class)) {
                    if (h.getNameAsString().equals(call.getNameAsString())
                            && h.findAll(BinaryExpr.class).stream().anyMatch(b -> b.getOperator() == BinaryExpr.Operator.DIVIDE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static FieldAccessExpr nodeField(Expression e, Set<String> nodeVars) {
        Expression x = e;
        while (x.isEnclosedExpr()) {
            x = x.asEnclosedExpr().getInner();
        }
        if (x instanceof FieldAccessExpr f && f.getScope() instanceof NameExpr n && nodeVars.contains(n.getNameAsString())) {
            return f;
        }
        return null;
    }

    private static boolean isOrdering(BinaryExpr.Operator op) {
        return op == BinaryExpr.Operator.LESS || op == BinaryExpr.Operator.GREATER
                || op == BinaryExpr.Operator.LESS_EQUALS || op == BinaryExpr.Operator.GREATER_EQUALS;
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static int endLine(Node n) {
        return n.getEnd().map(p -> p.line).orElse(0);
    }
}
