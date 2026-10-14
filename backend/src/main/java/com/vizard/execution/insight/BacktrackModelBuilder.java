package com.vizard.execution.insight;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.vizard.execution.insight.CodeModel.GraphSpec;
import com.vizard.execution.insight.CodeModel.SearchSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds backtracking searches in the AST. The shape every one of them shares:
 * <pre>
 *   void solve(..., int row) {
 *       if (row == n) { record a solution; return; }      ← goal test
 *       for (int col = 0; col &lt; n; col++) {                ← one branch per choice
 *           if (isSafe(row, col)) {                         ← feasibility check (prunes)
 *               board[row][col] = 1;                        ← choose
 *               solve(..., row + 1);                        ← explore the next level
 *               board[row][col] = 0;                        ← un-choose (backtrack)
 *           }
 *       }
 *   }
 * </pre>
 * or, without a loop, two calls that include and exclude one element ({@code a[i]}): subset problems.
 *
 * <p>What makes it a <i>search</i> rather than other recursion: the calls go one level deeper
 * ({@code row + 1}, {@code i + 1}), and there is a goal test at that depth. Merge sort
 * ({@code lo..mid}, {@code mid+1..hi}), Fibonacci ({@code n-1}, {@code n-2}), tree traversals and graph
 * DFS (whose call sits in a loop over neighbours, see {@link GraphModelBuilder}) do not have that shape.
 */
public final class BacktrackModelBuilder {

    private static final Set<String> PUSHES = Set.of("add", "push", "addLast", "offer", "offerLast", "addFirst");
    private static final Set<String> POPS = Set.of("remove", "removeLast", "pop", "pollLast", "removeFirst",
            "pollFirst");

    private BacktrackModelBuilder() {
    }

    public static List<SearchSpec> build(CompilationUnit cu) {
        List<SearchSpec> specs = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            find(m, cu).ifPresent(specs::add);
        }
        return specs;
    }

    public static Optional<SearchSpec> find(MethodDeclaration m, CompilationUnit cu) {
        if (m.getBody().isEmpty()) {
            return Optional.empty();
        }
        List<MethodCallExpr> calls = m.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(m.getNameAsString())
                        && (c.getScope().isEmpty() || c.getScope().get().isThisExpr())
                        && c.getArguments().size() == m.getParameters().size())
                .toList();
        if (calls.isEmpty()) {
            return Optional.empty();
        }
        for (GraphSpec g : GraphModelBuilder.find(m, cu)) {
            for (MethodCallExpr c : calls) {
                if (line(c) >= g.loopLine() && line(c) <= g.bodyEnd()) {
                    return Optional.empty(); // recursion over a vertex's neighbours: graph DFS
                }
            }
        }

        ForStmt loop = enclosingFor(calls.get(0), m);
        for (MethodCallExpr c : calls) {
            if (enclosingFor(c, m) != loop) {
                return Optional.empty(); // calls in different loops: not one choice per branch
            }
        }
        String loopVar = loop == null ? null : forVariable(loop);
        if (loop != null && loopVar == null) {
            return Optional.empty();
        }

        // The depth parameter: every call passes it plus one (row + 1), or passes the loop variable plus one.
        String depth = null;
        boolean viaLoopVar = false;
        NodeList<Parameter> params = m.getParameters();
        for (int k = 0; k < params.size() && depth == null; k++) {
            String p = params.get(k).getNameAsString();
            boolean allPlusOne = true;
            boolean allLoopPlusOne = loopVar != null;
            for (MethodCallExpr c : calls) {
                String arg = text(c.getArgument(k));
                allPlusOne &= arg.equals(p + "+1");
                allLoopPlusOne &= arg.equals(loopVar + "+1");
            }
            if (allPlusOne || allLoopPlusOne) {
                depth = p;
                viaLoopVar = !allPlusOne;
            }
        }
        String kind = loop != null ? "loop" : calls.size() >= 2 && depth != null ? "binary" : null;
        if (kind == null) {
            return Optional.empty();
        }

        // Goal test and bound checks: ifs that return, at the top level of the method body.
        IfStmt goal = null;
        List<IfStmt> exits = new ArrayList<>();
        for (Statement s : m.getBody().get().getStatements()) {
            if (s instanceof IfStmt test && returns(test.getThenStmt())) {
                exits.add(test);
            }
        }
        for (IfStmt test : exits) {
            boolean matches = depth != null ? mentions(test.getCondition(), depth) : isComparison(test.getCondition());
            if (matches) {
                goal = test;
                break;
            }
        }
        if (goal == null && !viaLoopVar) {
            return Optional.empty(); // recursion with no stopping test at the top is not a search
        }
        List<Integer> cutLines = new ArrayList<>();
        for (IfStmt test : exits) {
            if (test != goal) {
                cutLines.add(line(test.getCondition()));
            }
        }

        // Choose before each call and undo after it, in the same block.
        List<Statement> chooses = new ArrayList<>();
        List<Statement> undos = new ArrayList<>();
        Set<Integer> callLines = new LinkedHashSet<>();
        for (MethodCallExpr c : calls) {
            callLines.add(line(c));
            Statement holder = statementOf(c);
            if (holder == null || !(holder.getParentNode().orElse(null) instanceof BlockStmt block)) {
                continue;
            }
            List<Statement> list = block.getStatements();
            int at = list.indexOf(holder);
            List<Statement> before = new ArrayList<>();
            for (int i = at - 1; i >= 0 && !containsCall(list.get(i), calls); i--) {
                before.add(0, list.get(i));
            }
            List<Statement> mine = before.stream().filter(BacktrackModelBuilder::isChoice).toList();
            chooses.addAll(mine);
            for (int i = at + 1; i < list.size() && !containsCall(list.get(i), calls); i++) {
                if (undoes(list.get(i), mine)) {
                    undos.add(list.get(i));
                }
            }
        }
        chooses.removeIf(c -> undos.stream().anyMatch(u -> u == c)); // by identity: swap(a, k, i) twice is equal

        // Which choices are rejected before a call is made.
        List<Integer> rejectIfFalse = new ArrayList<>();
        List<Integer> rejectIfTrue = new ArrayList<>();
        Expression guardCode = null;
        for (MethodCallExpr c : calls) {
            // The nearest if around the call whose condition is a check, not the call itself
            // (if (solve(row + 1)) return true; is not a check).
            for (Node p = c.getParentNode().orElse(null); p != null && p != m && p != loop; p = p.getParentNode().orElse(null)) {
                if (p instanceof IfStmt guard && guard.getThenStmt().isAncestorOf(c) && guard != goal
                        && !exits.contains(guard) && !callsSelf(guard.getCondition(), m)) {
                    if (!rejectIfFalse.contains(line(guard.getCondition()))) {
                        rejectIfFalse.add(line(guard.getCondition()));
                    }
                    guardCode = guardCode == null ? guard.getCondition() : guardCode;
                    break;
                }
            }
        }
        if (loop != null) {
            for (IfStmt skip : loop.getBody().findAll(IfStmt.class)) {
                boolean continues = skip.getThenStmt() instanceof ContinueStmt
                        || !skip.getThenStmt().findAll(ContinueStmt.class).isEmpty();
                if (continues && line(skip) < line(calls.get(0))) {
                    rejectIfTrue.add(line(skip.getCondition()));
                    guardCode = guardCode == null ? skip.getCondition() : guardCode;
                }
            }
        }

        // Binary searches: the element decided at this level, and which call takes it.
        Expression item = null;
        int includeLine = 0;
        if (kind.equals("binary")) {
            for (ArrayAccessExpr a : m.findAll(ArrayAccessExpr.class)) {
                if (isName(a.getIndex(), depth) && a.getName().isNameExpr()) {
                    item = a;
                    break;
                }
            }
            for (MethodCallExpr c : calls) {
                String itemText = item == null ? null : text(item);
                boolean takes = itemText != null && c.getArguments().stream().anyMatch(arg -> text(arg).contains(itemText));
                Statement holder = statementOf(c);
                boolean afterChoice = holder != null && chooses.stream().anyMatch(ch -> line(ch) < line(holder)
                        && line(ch) >= line(m.getBody().get()) && sameBlock(ch, holder)
                        && nothingBetween(ch, holder, calls));
                if ((takes || afterChoice) && includeLine == 0) {
                    includeLine = line(c);
                }
            }
            if (includeLine == 0) {
                includeLine = line(calls.get(0));
            }
        }

        // Loop searches: the element a choice stands for, when it is read from an array (path.add(nums[i])).
        if (kind.equals("loop")) {
            outer:
            for (Statement ch : chooses) {
                for (ArrayAccessExpr a : ch.findAll(ArrayAccessExpr.class)) {
                    boolean target = a.getParentNode().orElse(null) instanceof AssignExpr assign && assign.getTarget() == a;
                    if (!target && isName(a.getIndex(), loopVar) && a.getName().isNameExpr()) {
                        item = a;
                        break outer;
                    }
                }
            }
        }

        // The array the choice is written to.
        String assignment = null;
        String shape = null;
        boolean cellOfDepthAndChoice = false;
        boolean depthGetsChoice = false;
        for (Statement ch : chooses) {
            for (AssignExpr a : ch.findAll(AssignExpr.class)) {
                if (!(a.getTarget() instanceof ArrayAccessExpr cell)) {
                    continue;
                }
                if (cell.getName() instanceof ArrayAccessExpr row && row.getName().isNameExpr()) {
                    assignment = row.getName().toString();
                    // board[row][col] with row the depth, or board[i][col] with col the depth (column by column)
                    shape = loopVar != null && isName(row.getIndex(), loopVar) && !isName(cell.getIndex(), loopVar)
                            ? "matrix-transposed" : "matrix";
                    Set<String> indexes = Set.of(text(row.getIndex()), text(cell.getIndex()));
                    cellOfDepthAndChoice = loopVar != null && indexes.contains(loopVar)
                            && (depth == null || indexes.contains(depth));
                } else if (cell.getName().isNameExpr()) {
                    assignment = cell.getName().toString();
                    shape = "vector";
                    depthGetsChoice = depth != null && isName(cell.getIndex(), depth) && loopVar != null
                            && isName(a.getValue(), loopVar);
                }
                break;
            }
            if (assignment != null) {
                break;
            }
        }

        String problem = classify(m, cu, kind, viaLoopVar, depth, loopVar, loop, chooses, undos, guardCode,
                assignment, cellOfDepthAndChoice, depthGetsChoice);

        Expression[] range = loop == null ? null : range(loop);
        return Optional.of(new SearchSpec(m.getNameAsString(), line(m), endLine(m), kind, problem, depth,
                goal == null ? 0 : line(goal.getCondition()), goal == null ? null : convertOrNull(bound(goal.getCondition(), depth)),
                cutLines, loop == null ? 0 : line(loop), loopVar,
                range == null ? null : CodeModelBuilder.convert(range[0]),
                range == null ? null : CodeModelBuilder.convert(range[1]),
                range != null && range[2] != null,
                rejectIfFalse, rejectIfTrue, List.copyOf(callLines),
                item == null ? null : CodeModelBuilder.convert(item), includeLine,
                undos.stream().map(BacktrackModelBuilder::line).distinct().toList(), assignment, shape,
                stateNames(chooses, undos)));
    }

    // ---------- which problem ------------------------------------------------------------

    private static String classify(MethodDeclaration m, CompilationUnit cu, String kind, boolean viaLoopVar,
                                   String depth, String loopVar, ForStmt loop, List<Statement> chooses,
                                   List<Statement> undos, Expression guardCode, String assignment,
                                   boolean cellOfDepthAndChoice, boolean depthGetsChoice) {
        if (kind.equals("binary") || viaLoopVar) {
            return "subsets"; // include/exclude each element, or extend a combination with a later element
        }
        String guard = guardCode == null ? "" : text(guardCode);
        if (depth != null && loopVar != null && (guard.contains("[" + depth + "+" + loopVar)
                || guard.contains("[" + depth + "-" + loopVar) || guard.contains("[" + loopVar + "+" + depth)
                || guard.contains("[" + loopVar + "-" + depth))) {
            return "n-queens"; // diagonals indexed by row + col and row - col
        }
        if (loopVar != null && (guard.contains("[" + loopVar + "]") && marksTrue(chooses, loopVar)
                || guard.contains(".contains(") || guard.contains(".indexOf("))) {
            return "permutations"; // pick any element not used yet
        }
        if (loopVar != null && depth != null && swapsBackAndForth(chooses, undos, depth, loopVar)) {
            return "permutations"; // swap(a, k, i); permute(k + 1); swap(a, k, i);
        }
        if (cellOfDepthAndChoice) {
            return "n-queens"; // a queen on board[row][col]
        }
        if (depthGetsChoice) {
            String helpers = guardCode == null ? "" : helperCode(guardCode, cu, depth);
            String all = guard + " " + helpers;
            if (guardCode != null && readsAdjacency(guardCode, cu, depth, assignment)) {
                return "graph-coloring"; // color[v] = c, checked against v's neighbours
            }
            if (all.contains("Math.abs") || all.contains(assignment + "[") && all.contains("-")) {
                return "n-queens"; // queens[row] = col, checked along the diagonals
            }
        }
        return "backtracking";
    }

    private static boolean marksTrue(List<Statement> chooses, String loopVar) {
        for (Statement s : chooses) {
            for (AssignExpr a : s.findAll(AssignExpr.class)) {
                if (a.getTarget() instanceof ArrayAccessExpr cell && isName(cell.getIndex(), loopVar)
                        && a.getValue() instanceof BooleanLiteralExpr b && b.getValue()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean swapsBackAndForth(List<Statement> chooses, List<Statement> undos, String depth,
                                             String loopVar) {
        for (Statement s : chooses) {
            if (s instanceof ExpressionStmt e && e.getExpression() instanceof MethodCallExpr call) {
                Set<String> args = new LinkedHashSet<>();
                call.getArguments().forEach(a -> args.add(text(a)));
                boolean both = args.contains(depth) && args.contains(loopVar);
                boolean repeated = undos.stream().anyMatch(u -> text(u).equals(text(s)));
                if (both && repeated) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The bodies of methods called in the guard (isSafe(...)), as text. */
    private static String helperCode(Expression guard, CompilationUnit cu, String depth) {
        StringBuilder sb = new StringBuilder();
        for (MethodCallExpr call : guard.findAll(MethodCallExpr.class)) {
            helper(call, cu).ifPresent(h -> sb.append(h.getBody().map(BacktrackModelBuilder::text).orElse("")));
        }
        return sb.toString();
    }

    /**
     * The check reads the graph around the vertex being coloured: {@code graph[v][i]} or
     * {@code adj.get(v)}, with v the depth (mapped through a helper's parameters).
     */
    private static boolean readsAdjacency(Expression guard, CompilationUnit cu, String depth, String assignment) {
        if (readsRowOf(guard, depth, assignment)) {
            return true;
        }
        for (MethodCallExpr call : guard.findAll(MethodCallExpr.class)) {
            Optional<MethodDeclaration> h = helper(call, cu);
            if (h.isEmpty() || h.get().getBody().isEmpty()) {
                continue;
            }
            for (int k = 0; k < call.getArguments().size(); k++) {
                if (isName(call.getArgument(k), depth)) {
                    String inner = h.get().getParameter(k).getNameAsString();
                    if (readsRowOf(h.get().getBody().get(), inner, assignment)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean readsRowOf(Node code, String vertex, String assignment) {
        for (ArrayAccessExpr a : code.findAll(ArrayAccessExpr.class)) {
            if (a.getName() instanceof ArrayAccessExpr row && isName(row.getIndex(), vertex)
                    && !row.getName().toString().equals(assignment)) {
                return true;
            }
        }
        for (MethodCallExpr c : code.findAll(MethodCallExpr.class)) {
            if (c.getNameAsString().equals("get") && c.getArguments().size() == 1 && isName(c.getArgument(0), vertex)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<MethodDeclaration> helper(MethodCallExpr call, CompilationUnit cu) {
        if (call.getScope().isPresent() && !call.getScope().get().isThisExpr()) {
            return Optional.empty();
        }
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(d -> d.getNameAsString().equals(call.getNameAsString())
                        && d.getParameters().size() == call.getArguments().size())
                .findFirst();
    }

    // ---------- choose / undo -------------------------------------------------------------

    /** board (of board[r][c] = 1), path (of path.add(x)), a (of swap(a, k, i)). */
    private static List<String> stateNames(List<Statement> chooses, List<Statement> undos) {
        Set<String> names = new LinkedHashSet<>();
        List<Statement> all = new ArrayList<>(chooses);
        all.addAll(undos);
        for (Statement s : all) {
            Expression x = ((ExpressionStmt) s).getExpression();
            Expression target = x instanceof AssignExpr a ? a.getTarget()
                    : x instanceof MethodCallExpr c && c.getScope().isPresent() ? c.getScope().get() : null;
            while (target instanceof ArrayAccessExpr a) {
                target = a.getName();
            }
            if (target != null) {
                names.add(target.toString());
            }
            if (x instanceof MethodCallExpr c && c.getScope().isEmpty()) {
                c.getArguments().stream().filter(Expression::isNameExpr).forEach(a -> names.add(a.toString()));
            }
            for (AssignExpr chained : x.findAll(AssignExpr.class)) {
                Expression t = chained.getTarget();
                while (t instanceof ArrayAccessExpr a) {
                    t = a.getName();
                }
                names.add(t.toString());
            }
        }
        return List.copyOf(names);
    }

    /** board[r][c] = 1, used[i] = true, path.add(x), stack.push(x), swap(a, k, i), x += a[i]. */
    private static boolean isChoice(Statement s) {
        if (!(s instanceof ExpressionStmt e)) {
            return false;
        }
        Expression x = e.getExpression();
        if (x instanceof AssignExpr a) {
            return a.getTarget().isArrayAccessExpr() || a.getTarget().isNameExpr() || a.getTarget().isFieldAccessExpr();
        }
        if (x instanceof MethodCallExpr c) {
            return PUSHES.contains(c.getNameAsString()) && c.getScope().isPresent()
                    || c.getScope().isEmpty() && c.getArguments().size() >= 2;
        }
        return x.isUnaryExpr();
    }

    /** Puts back what a choice changed: the same cell reassigned, a pop/remove on the same list, the same swap. */
    private static boolean undoes(Statement s, List<Statement> chooses) {
        if (!(s instanceof ExpressionStmt e)) {
            return false;
        }
        Expression x = e.getExpression();
        for (Statement ch : chooses) {
            Expression c = ((ExpressionStmt) ch).getExpression();
            if (x instanceof AssignExpr a && c instanceof AssignExpr b && text(a.getTarget()).equals(text(b.getTarget()))) {
                return true;
            }
            if (x instanceof MethodCallExpr pop && c instanceof MethodCallExpr push && POPS.contains(pop.getNameAsString())
                    && pop.getScope().isPresent() && push.getScope().isPresent()
                    && text(pop.getScope().get()).equals(text(push.getScope().get()))) {
                return true;
            }
            if (text(x).equals(text(c)) && x instanceof MethodCallExpr) {
                return true; // swap(a, k, i) again
            }
            if (x.isAssignExpr() && c.isAssignExpr() && x.asAssignExpr().getOperator() != AssignExpr.Operator.ASSIGN
                    && text(x.asAssignExpr().getTarget()).equals(text(c.asAssignExpr().getTarget()))) {
                return true; // sum += a[i] ... sum -= a[i]
            }
        }
        return false;
    }

    // ---------- helpers -------------------------------------------------------------------

    private static ForStmt enclosingFor(Node n, MethodDeclaration m) {
        Node p = n.getParentNode().orElse(null);
        while (p != null && p != m) {
            if (p instanceof ForStmt f) {
                return f;
            }
            if (p instanceof WhileStmt || p instanceof ForEachStmt) {
                return null;
            }
            p = p.getParentNode().orElse(null);
        }
        return null;
    }

    /** {from, to, inclusive marker (non-null when <=)} of {@code for (int col = 0; col < n; col++)}. */
    private static Expression[] range(ForStmt loop) {
        Expression from = null;
        for (Expression init : loop.getInitialization()) {
            if (init instanceof VariableDeclarationExpr d && d.getVariable(0).getInitializer().isPresent()) {
                from = d.getVariable(0).getInitializer().get();
            } else if (init instanceof AssignExpr a) {
                from = a.getValue();
            }
        }
        if (from == null || loop.getCompare().isEmpty() || !(loop.getCompare().get() instanceof BinaryExpr cmp)) {
            return null;
        }
        boolean less = cmp.getOperator() == BinaryExpr.Operator.LESS;
        boolean lessEq = cmp.getOperator() == BinaryExpr.Operator.LESS_EQUALS;
        if (!less && !lessEq) {
            return null;
        }
        return new Expression[]{from, cmp.getRight(), lessEq ? cmp : null};
    }

    /** The side of the goal test that is the target depth: {@code row == n} → n. */
    private static Expression bound(Expression condition, String depth) {
        for (BinaryExpr b : condition.findAll(BinaryExpr.class)) {
            if (!isComparisonOperator(b.getOperator())) {
                continue;
            }
            if (depth != null && mentions(b.getLeft(), depth)) {
                return b.getRight();
            }
            if (depth != null && mentions(b.getRight(), depth)) {
                return b.getLeft();
            }
            if (depth == null) {
                return b.getLeft().findFirst(MethodCallExpr.class).isPresent() || b.getLeft().isMethodCallExpr()
                        ? b.getRight() : b.getLeft();
            }
        }
        return null;
    }

    private static Expr convertOrNull(Expression e) {
        return e == null ? null : CodeModelBuilder.convert(e);
    }

    private static boolean isComparison(Expression e) {
        return e.findAll(BinaryExpr.class).stream().anyMatch(b -> isComparisonOperator(b.getOperator()));
    }

    private static boolean isComparisonOperator(BinaryExpr.Operator op) {
        return op == BinaryExpr.Operator.EQUALS || op == BinaryExpr.Operator.GREATER_EQUALS
                || op == BinaryExpr.Operator.GREATER || op == BinaryExpr.Operator.LESS_EQUALS
                || op == BinaryExpr.Operator.LESS;
    }

    private static boolean returns(Statement s) {
        return s instanceof ReturnStmt || !s.findAll(ReturnStmt.class).isEmpty();
    }

    private static boolean mentions(Expression e, String name) {
        if (e.isNameExpr() && e.asNameExpr().getNameAsString().equals(name)) {
            return true;
        }
        return e.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(name));
    }

    private static boolean callsSelf(Expression e, MethodDeclaration m) {
        return e.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals(m.getNameAsString()))
                || e instanceof MethodCallExpr c && c.getNameAsString().equals(m.getNameAsString());
    }

    private static Statement statementOf(Node n) {
        Node p = n;
        while (p != null && !(p.getParentNode().orElse(null) instanceof BlockStmt)) {
            p = p.getParentNode().orElse(null);
        }
        return p instanceof Statement s ? s : null;
    }

    private static boolean containsCall(Statement s, List<MethodCallExpr> calls) {
        return calls.stream().anyMatch(s::isAncestorOf);
    }

    private static boolean sameBlock(Node a, Node b) {
        return a.getParentNode().isPresent() && a.getParentNode().equals(b.getParentNode());
    }

    private static boolean nothingBetween(Statement choice, Statement call, List<MethodCallExpr> calls) {
        if (!(call.getParentNode().orElse(null) instanceof BlockStmt block)) {
            return false;
        }
        List<Statement> list = block.getStatements();
        for (int i = list.indexOf(choice) + 1; i < list.indexOf(call); i++) {
            if (containsCall(list.get(i), calls)) {
                return false;
            }
        }
        return true;
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

    private static boolean isName(Expression e, String name) {
        Expression x = e;
        while (x.isEnclosedExpr()) {
            x = x.asEnclosedExpr().getInner();
        }
        return name != null && x.isNameExpr() && x.asNameExpr().getNameAsString().equals(name);
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static int endLine(Node n) {
        return n.getEnd().map(p -> p.line).orElse(0);
    }

    private static String text(Node n) {
        String raw = n.getTokenRange().map(Object::toString).orElseGet(n::toString);
        return raw.replaceAll("\\s+", "");
    }
}
