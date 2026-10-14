package com.vizard.execution.insight;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.vizard.execution.insight.CodeModel.GraphSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds graph traversals in the AST. The defining shape of every graph algorithm is a
 * <b>loop over the neighbours of a vertex</b>:
 * <pre>
 *   for (int v : adj.get(u))          adjacency list (List&lt;List&lt;Integer&gt;&gt;, List&lt;Integer&gt;[], Map)
 *   for (int[] e : adj.get(u))        weighted list: e = {to, weight}
 *   for (int v = 0; v &lt; n; v++)       adjacency matrix: the body tests g[u][v]
 *       if (g[u][v] != 0 ...)
 * </pre>
 * Around that loop the same AST shows the rest of the traversal's state: the array or set
 * that marks vertices visited, the queue/stack/priority queue they wait in, a distance array
 * and its relaxation, a parent array. Names are never used, only how variables are used.
 *
 * <p>A loop only counts as a neighbour loop if the neighbour is then <i>used as a vertex</i>
 * (indexes a 1-D array, goes into a collection, or is passed to a call). That rules out loops
 * over DP tables and matrix products, which index only 2-D arrays.
 */
public final class GraphModelBuilder {

    private static final Set<String> ADDS = Set.of("add", "offer", "push", "addLast", "addFirst", "offerLast",
            "offerFirst");
    private static final Set<String> REMOVES = Set.of("poll", "pop", "remove", "removeFirst", "removeLast",
            "pollFirst", "pollLast");
    private static final Set<String> STACK_ADDS = Set.of("push", "addFirst", "offerFirst");
    private static final Set<String> STACK_REMOVES = Set.of("pop", "removeFirst", "pollFirst");
    private static final Set<String> SET_LOOKUPS = Set.of("contains", "containsKey", "get", "getOrDefault");

    private GraphModelBuilder() {
    }

    public static List<GraphSpec> build(CompilationUnit cu) {
        List<GraphSpec> specs = new ArrayList<>();
        for (CallableDeclaration<?> m : cu.findAll(CallableDeclaration.class)) {
            specs.addAll(find(m, cu));
        }
        return specs;
    }

    /** The neighbour loops of one method, with the traversal state around each. */
    public static List<GraphSpec> find(CallableDeclaration<?> m, CompilationUnit cu) {
        List<GraphSpec> specs = new ArrayList<>();
        for (ForEachStmt loop : m.findAll(ForEachStmt.class)) {
            graphOf(loop.getIterable()).ifPresent(g -> {
                String element = loop.getVariableDeclarator().getNameAsString();
                analyse(m, cu, loop, loop.getBody(), g[0], g[1], "list", element, elementKind(loop))
                        .ifPresent(specs::add);
            });
        }
        for (ForStmt loop : m.findAll(ForStmt.class)) {
            String v = forVariable(loop);
            if (v == null) {
                continue;
            }
            matrixRow(loop.getBody(), v).ifPresent(g ->
                    analyse(m, cu, loop, loop.getBody(), g[0], g[1], "matrix", v, "vertex").ifPresent(specs::add));
            indexedList(loop, v).ifPresent(found ->
                    analyse(m, cu, loop, loop.getBody(), found.graph, found.vertex, "list", found.element,
                            found.kind).ifPresent(specs::add));
        }
        for (WhileStmt loop : m.findAll(WhileStmt.class)) {
            iteratorLoop(m, loop).ifPresent(found ->
                    analyse(m, cu, loop, loop.getBody(), found.graph, found.vertex, "list", found.element,
                            found.kind).ifPresent(specs::add));
        }
        return specs;
    }

    // ---------- the neighbour loop ------------------------------------------------------

    /**
     * The iterator form (common in textbook code):
     * {@code Iterator<Integer> it = adj[u].listIterator(); while (it.hasNext()) { int v = it.next(); ... }}.
     */
    private static Optional<IndexedList> iteratorLoop(CallableDeclaration<?> m, WhileStmt loop) {
        Expression c = unwrap(loop.getCondition());
        if (!(c instanceof MethodCallExpr hasNext) || !hasNext.getNameAsString().equals("hasNext")
                || hasNext.getScope().isEmpty() || !hasNext.getScope().get().isNameExpr()) {
            return Optional.empty();
        }
        String it = hasNext.getScope().get().asNameExpr().getNameAsString();
        for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
            if (!d.getNameAsString().equals(it) || d.getInitializer().isEmpty()
                    || !(unwrap(d.getInitializer().get()) instanceof MethodCallExpr make)
                    || !(make.getNameAsString().equals("iterator") || make.getNameAsString().equals("listIterator"))
                    || make.getScope().isEmpty()) {
                continue;
            }
            Optional<Expression[]> g = graphOf(make.getScope().get());
            if (g.isEmpty()) {
                continue;
            }
            for (VariableDeclarator x : loop.getBody().findAll(VariableDeclarator.class)) {
                if (x.getInitializer().isPresent() && unwrap(x.getInitializer().get()) instanceof MethodCallExpr next
                        && next.getNameAsString().equals("next") && next.getScope().isPresent()
                        && isName(next.getScope().get(), it)) {
                    return Optional.of(new IndexedList(g.get()[0], g.get()[1], x.getNameAsString(),
                            kindOfType(x.getType().asString())));
                }
            }
        }
        return Optional.empty();
    }

    /** {@code G.get(u)}, {@code G.getOrDefault(u, ...)} or {@code G[u]}: returns {G, u}. */
    private static Optional<Expression[]> graphOf(Expression iterable) {
        Expression e = unwrap(iterable);
        if (e instanceof MethodCallExpr call && (call.getNameAsString().equals("get")
                || call.getNameAsString().equals("getOrDefault")) && !call.getArguments().isEmpty()
                && call.getScope().isPresent() && isVariable(call.getScope().get())
                && unwrap(call.getArgument(0)).isNameExpr()) {
            return Optional.of(new Expression[]{call.getScope().get(), unwrap(call.getArgument(0))});
        }
        if (e instanceof ArrayAccessExpr access && isVariable(access.getName()) && unwrap(access.getIndex()).isNameExpr()) {
            return Optional.of(new Expression[]{access.getName(), unwrap(access.getIndex())});
        }
        return Optional.empty();
    }

    /** In the body of a loop over v: {@code G[u][v]} with u another plain name. Returns {G, u}. */
    private static Optional<Expression[]> matrixRow(Statement body, String v) {
        for (ArrayAccessExpr outer : body.findAll(ArrayAccessExpr.class)) {
            if (outer.getName() instanceof ArrayAccessExpr inner && isName(outer.getIndex(), v)
                    && isVariable(inner.getName()) && unwrap(inner.getIndex()).isNameExpr()
                    && !isName(inner.getIndex(), v)) {
                return Optional.of(new Expression[]{inner.getName(), unwrap(inner.getIndex())});
            }
        }
        return Optional.empty();
    }

    private record IndexedList(Expression graph, Expression vertex, String element, String kind) {
    }

    /**
     * {@code for (int i = 0; i < G.get(u).size(); i++) { int v = G.get(u).get(i); ... }}:
     * the same neighbour loop written with an index.
     */
    private static Optional<IndexedList> indexedList(ForStmt loop, String index) {
        if (loop.getCompare().isEmpty()) {
            return Optional.empty();
        }
        for (MethodCallExpr size : loop.getCompare().get().findAll(MethodCallExpr.class)) {
            if (!size.getNameAsString().equals("size") || size.getScope().isEmpty()) {
                continue;
            }
            Optional<Expression[]> g = graphOf(size.getScope().get());
            if (g.isEmpty()) {
                continue;
            }
            for (VariableDeclarator d : loop.getBody().findAll(VariableDeclarator.class)) {
                if (d.getInitializer().isPresent() && unwrap(d.getInitializer().get()) instanceof MethodCallExpr get
                        && get.getNameAsString().equals("get") && get.getArguments().size() == 1
                        && isName(get.getArgument(0), index) && get.getScope().isPresent()
                        && text(get.getScope().get()).equals(text(size.getScope().get()))) {
                    return Optional.of(new IndexedList(g.get()[0], g.get()[1], d.getNameAsString(),
                            kindOfType(d.getType().asString())));
                }
            }
        }
        return Optional.empty();
    }

    // ---------- the state around it ------------------------------------------------------

    /**
     * @param element the loop variable; {@code kind} says what it holds: "vertex" (an int),
     *                "array" (int[] edge) or "object" (an Edge-like class)
     */
    private static Optional<GraphSpec> analyse(CallableDeclaration<?> m, CompilationUnit cu, Statement loop,
                                               Statement body, Expression graph, Expression vertexExpr,
                                               String shape, String element, String kind) {
        String vertex = vertexExpr.asNameExpr().getNameAsString();
        if (vertex.equals(element)) {
            return Optional.empty();
        }

        // Which expressions mean "the neighbour vertex" and "the edge weight" inside the body.
        String target;
        String weight = null;
        Set<String> targets = new LinkedHashSet<>();
        Set<String> weights = new LinkedHashSet<>();
        if (kind.equals("vertex")) {
            target = "";
            targets.add(element);
            if (shape.equals("matrix")) {
                weight = "cell";
                weights.add(text(graph) + "[" + vertex + "][" + element + "]");
            }
        } else {
            List<String> parts = parts(body, element, kind);
            target = null;
            for (String part : parts) {
                Set<String> forms = aliases(body, element + part);
                if (target == null && usedAsVertex(body, forms)) {
                    target = part;
                    targets.addAll(forms);
                }
            }
            if (target == null) {
                return Optional.empty();
            }
            for (String part : parts) {
                if (!part.equals(target)) {
                    weight = part;
                    weights.addAll(aliases(body, element + part));
                    break;
                }
            }
        }
        targets.addAll(aliasesOfAll(body, targets));
        if (shape.equals("matrix") && conditions(body).stream()
                .noneMatch(c -> text(c).contains(text(graph) + "[" + vertex + "][" + element + "]"))) {
            return Optional.empty(); // the cell must be tested: is there an edge u → v?
        }
        if (!usedAsVertex(body, targets) && !recursesWith(m, body, targets)) {
            return Optional.empty(); // a loop over numbers, not over neighbours
        }

        String visited = visited(m, body, targets);
        String[] dist = distance(m, body, targets, vertex, weights, visited);
        String parent = parent(body, targets, vertex);
        String[] frontier = frontier(m, cu, body, targets, vertex);
        boolean recursive = recursesWith(m, body, targets);
        boolean minScan = dist[0] != null && frontier[0] == null && selectsMinimum(m, cu, loop);

        return Optional.of(new GraphSpec(name(m), line(m), endLine(m), CodeModelBuilder.convert(graph), shape,
                vertex, element, line(loop), bodyStart(body), bodyEnd(body), target, weight, visited, dist[0],
                parent, frontier[0], frontier[1], frontier[2], recursive, "relax".equals(dist[1]),
                "levels".equals(dist[1]), minScan));
    }

    /** For an int[] edge: the indexes used ("[0]", "[1]"); for an object: the fields used (".to"). */
    private static List<String> parts(Statement body, String element, String kind) {
        Set<String> parts = new LinkedHashSet<>();
        if (kind.equals("array")) {
            for (ArrayAccessExpr a : body.findAll(ArrayAccessExpr.class)) {
                if (isName(a.getName(), element) && a.getIndex().isIntegerLiteralExpr()) {
                    parts.add("[" + a.getIndex() + "]");
                }
            }
        } else {
            for (FieldAccessExpr f : body.findAll(FieldAccessExpr.class)) {
                if (isName(f.getScope(), element)) {
                    parts.add("." + f.getNameAsString());
                }
            }
        }
        return new ArrayList<>(parts);
    }

    /** The expression itself plus local names assigned from it: {@code int v = e[0];}. */
    private static Set<String> aliases(Statement body, String expression) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(expression);
        for (VariableDeclarator d : body.findAll(VariableDeclarator.class)) {
            if (d.getInitializer().isPresent() && text(unwrap(d.getInitializer().get())).equals(expression)) {
                forms.add(d.getNameAsString());
            }
        }
        return forms;
    }

    private static Set<String> aliasesOfAll(Statement body, Set<String> expressions) {
        Set<String> all = new LinkedHashSet<>();
        for (String e : expressions) {
            all.addAll(aliases(body, e));
        }
        return all;
    }

    /** One of the forms indexes a 1-D array, or is an argument of a call. */
    private static boolean usedAsVertex(Statement body, Set<String> forms) {
        for (ArrayAccessExpr a : body.findAll(ArrayAccessExpr.class)) {
            if (forms.contains(text(unwrap(a.getIndex()))) && isVariable(a.getName())
                    && !(a.getParentNode().orElse(null) instanceof ArrayAccessExpr parent && parent.getName() == a)) {
                return true;
            }
        }
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            boolean collectionCall = call.getScope().isPresent() && (ADDS.contains(call.getNameAsString())
                    || SET_LOOKUPS.contains(call.getNameAsString()) || call.getNameAsString().equals("put"));
            if (!collectionCall) {
                continue; // helper calls such as isSafe(board, row, col) don't make a number a vertex
            }
            for (Expression arg : call.getArguments()) {
                if (forms.contains(text(unwrap(arg)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean recursesWith(CallableDeclaration<?> m, Statement body, Set<String> targets) {
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals(m.getNameAsString()) && call.getScope().isEmpty()
                    && call.getArguments().stream().anyMatch(a -> targets.contains(text(unwrap(a))))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checked for the neighbour ({@code !visited[v]}, {@code !seen.contains(v)}) and marked somewhere
     * in the method ({@code visited[x] = true}, {@code seen.add(x)}).
     */
    private static String visited(CallableDeclaration<?> m, Statement body, Set<String> targets) {
        for (Expression condition : conditions(body)) {
            for (ArrayAccessExpr a : condition.findAll(ArrayAccessExpr.class)) {
                if (targets.contains(text(unwrap(a.getIndex()))) && isVariable(a.getName())
                        && marksArray(m, text(a.getName()))) {
                    return text(a.getName());
                }
            }
            for (MethodCallExpr call : condition.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("contains") && call.getScope().isPresent()
                        && call.getArguments().size() == 1 && targets.contains(text(unwrap(call.getArgument(0))))) {
                    String set = text(call.getScope().get());
                    boolean marked = m.findAll(MethodCallExpr.class).stream().anyMatch(c ->
                            c.getNameAsString().equals("add") && c.getScope().isPresent()
                                    && text(c.getScope().get()).equals(set));
                    if (marked) {
                        return set;
                    }
                }
            }
        }
        return null;
    }

    /** {@code name[...] = true} or {@code name[...] = 1} somewhere in the method. */
    private static boolean marksArray(CallableDeclaration<?> m, String name) {
        for (AssignExpr assign : m.findAll(AssignExpr.class)) {
            if (assign.getOperator() == AssignExpr.Operator.ASSIGN && assign.getTarget() instanceof ArrayAccessExpr a
                    && text(a.getName()).equals(name)) {
                Expression value = unwrap(assign.getValue());
                if (value instanceof BooleanLiteralExpr b && b.getValue()
                        || value.isIntegerLiteralExpr() && value.toString().equals("1")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code D[v] = <something using D>} in the body, e.g. {@code dist[v] = dist[u] + w}.
     * Returns {name, "relax" | "levels" | "other"}: a relaxation is guarded by a comparison with
     * D[v] and adds a weight; {@code + 1} with no weight is a BFS level.
     */
    private static String[] distance(CallableDeclaration<?> m, Statement body, Set<String> targets, String vertex,
                                     Set<String> weights, String visited) {
        for (AssignExpr assign : body.findAll(AssignExpr.class)) {
            String name = null;
            Expression value = assign.getValue();
            if (assign.getTarget() instanceof ArrayAccessExpr a && targets.contains(text(unwrap(a.getIndex())))
                    && isVariable(a.getName())) {
                name = text(a.getName());
            }
            if (name == null || name.equals(visited) || !readsStructure(body, value, name)) {
                continue;
            }
            return new String[]{name, kindOfUpdate(assign, value, name, targets, weights, body)};
        }
        for (MethodCallExpr put : body.findAll(MethodCallExpr.class)) {
            if (put.getNameAsString().equals("put") && put.getScope().isPresent() && put.getArguments().size() == 2
                    && targets.contains(text(unwrap(put.getArgument(0))))) {
                String name = text(put.getScope().get());
                if (!name.equals(visited) && readsStructure(body, put.getArgument(1), name)) {
                    return new String[]{name, kindOfUpdate(put, put.getArgument(1), name, targets, weights, body)};
                }
            }
        }
        return new String[]{null, null};
    }

    /** The value reads D (D[u], D.get(u)) directly or through a local: {@code int nd = dist[u] + w}. */
    private static boolean readsStructure(Statement body, Expression value, String name) {
        if (mentions(value, name)) {
            return true;
        }
        for (NameExpr n : value.findAll(NameExpr.class)) {
            for (VariableDeclarator d : body.findAll(VariableDeclarator.class)) {
                if (d.getNameAsString().equals(n.getNameAsString()) && d.getInitializer().isPresent()
                        && mentions(d.getInitializer().get(), name)) {
                    return true;
                }
            }
        }
        if (value.isNameExpr()) {
            for (VariableDeclarator d : body.findAll(VariableDeclarator.class)) {
                if (d.getNameAsString().equals(value.asNameExpr().getNameAsString()) && d.getInitializer().isPresent()
                        && mentions(d.getInitializer().get(), name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mentions(Expression e, String name) {
        for (ArrayAccessExpr a : e.findAll(ArrayAccessExpr.class)) {
            if (text(a.getName()).equals(name)) {
                return true;
            }
        }
        if (e instanceof ArrayAccessExpr a && text(a.getName()).equals(name)) {
            return true;
        }
        for (MethodCallExpr c : e.findAll(MethodCallExpr.class)) {
            if (c.getScope().isPresent() && text(c.getScope().get()).equals(name)) {
                return true;
            }
        }
        return e instanceof MethodCallExpr c && c.getScope().isPresent() && text(c.getScope().get()).equals(name);
    }

    private static String kindOfUpdate(Node update, Expression value, String name, Set<String> targets,
                                       Set<String> weights, Statement body) {
        String full = fullValue(body, value);
        boolean addsOne = full.matches(".*\\+\\s*1\\b.*") && weights.stream().noneMatch(full::contains);
        if (addsOne) {
            return "levels";
        }
        boolean adds = full.contains("+");
        boolean guarded = false;
        for (Node n = update.getParentNode().orElse(null); n != null; n = n.getParentNode().orElse(null)) {
            if (n instanceof IfStmt guard && comparesWith(guard.getCondition(), name, targets)) {
                guarded = true;
            }
            if (n == body) {
                break; // the body itself may be the guarding if (a loop body without braces)
            }
        }
        return adds && guarded ? "relax" : "other";
    }

    /** The value with one level of locals expanded: {@code nd} → {@code dist[u] + w}. */
    private static String fullValue(Statement body, Expression value) {
        StringBuilder sb = new StringBuilder(text(value));
        for (NameExpr n : allNames(value)) {
            for (VariableDeclarator d : body.findAll(VariableDeclarator.class)) {
                if (d.getNameAsString().equals(n.getNameAsString()) && d.getInitializer().isPresent()) {
                    sb.append(" ; ").append(text(d.getInitializer().get()));
                }
            }
        }
        return sb.toString();
    }

    private static List<NameExpr> allNames(Expression e) {
        List<NameExpr> names = new ArrayList<>(e.findAll(NameExpr.class));
        if (e instanceof NameExpr n && !names.contains(n)) {
            names.add(n);
        }
        return names;
    }

    /** The condition compares D[v] (or D.get(v)) with something: {@code dist[u] + w < dist[v]}. */
    private static boolean comparesWith(Expression condition, String name, Set<String> targets) {
        for (BinaryExpr b : condition.findAll(BinaryExpr.class)) {
            boolean ordering = b.getOperator() == BinaryExpr.Operator.LESS
                    || b.getOperator() == BinaryExpr.Operator.GREATER
                    || b.getOperator() == BinaryExpr.Operator.LESS_EQUALS
                    || b.getOperator() == BinaryExpr.Operator.GREATER_EQUALS;
            if (ordering && (isCellOf(b.getLeft(), name, targets) || isCellOf(b.getRight(), name, targets))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCellOf(Expression e, String name, Set<String> targets) {
        Expression x = unwrap(e);
        if (x instanceof ArrayAccessExpr a) {
            return text(a.getName()).equals(name) && targets.contains(text(unwrap(a.getIndex())));
        }
        if (x instanceof MethodCallExpr c && c.getScope().isPresent() && !c.getArguments().isEmpty()) {
            return text(c.getScope().get()).equals(name) && SET_LOOKUPS.contains(c.getNameAsString())
                    && targets.contains(text(unwrap(c.getArgument(0))));
        }
        return false;
    }

    /** {@code parent[v] = u} or {@code parent.put(v, u)}. */
    private static String parent(Statement body, Set<String> targets, String vertex) {
        for (AssignExpr assign : body.findAll(AssignExpr.class)) {
            if (assign.getTarget() instanceof ArrayAccessExpr a && targets.contains(text(unwrap(a.getIndex())))
                    && isName(assign.getValue(), vertex) && isVariable(a.getName())) {
                return text(a.getName());
            }
        }
        for (MethodCallExpr put : body.findAll(MethodCallExpr.class)) {
            if (put.getNameAsString().equals("put") && put.getScope().isPresent() && put.getArguments().size() == 2
                    && targets.contains(text(unwrap(put.getArgument(0)))) && isName(put.getArgument(1), vertex)) {
                return text(put.getScope().get());
            }
        }
        return null;
    }

    /**
     * The collection the neighbour is added to inside the loop, if the method also takes vertices out
     * of it. Returns {name, accessor from element to vertex, "queue" | "stack" | "priority"}.
     */
    private static String[] frontier(CallableDeclaration<?> m, CompilationUnit cu, Statement body,
                                     Set<String> targets, String vertex) {
        for (MethodCallExpr add : body.findAll(MethodCallExpr.class)) {
            if (!ADDS.contains(add.getNameAsString()) || add.getScope().isEmpty() || add.getArguments().size() != 1) {
                continue;
            }
            Expression arg = add.getArgument(0);
            boolean holdsNeighbour = targets.contains(text(unwrap(arg)))
                    || arg.findAll(NameExpr.class).stream().anyMatch(n -> targets.contains(n.getNameAsString()))
                    || arg.findAll(ArrayAccessExpr.class).stream().anyMatch(a -> targets.contains(text(a)));
            if (!holdsNeighbour) {
                continue;
            }
            String name = text(add.getScope().get());
            List<MethodCallExpr> removals = m.findAll(MethodCallExpr.class).stream()
                    .filter(c -> REMOVES.contains(c.getNameAsString()) && c.getArguments().isEmpty()
                            && c.getScope().isPresent() && text(c.getScope().get()).equals(name))
                    .toList();
            if (removals.isEmpty()) {
                continue; // added to a result list, not a frontier
            }
            String removal = removals.get(0).getNameAsString();
            String order;
            if (declaredAsPriorityQueue(cu, name)) {
                order = "priority";
            } else if (STACK_ADDS.contains(add.getNameAsString()) ? STACK_REMOVES.contains(removal)
                    : removal.equals("removeLast") || removal.equals("pollLast")) {
                order = "stack"; // both ends the same: last in, first out
            } else {
                order = "queue";
            }
            return new String[]{name, vertexAccessor(m, removals.get(0), vertex), order};
        }
        return new String[]{null, null, null};
    }

    /** {@code int u = q.poll()} → ""; {@code int[] top = pq.poll(); int u = top[1];} → "[1]". */
    private static String vertexAccessor(CallableDeclaration<?> m, MethodCallExpr removal, String vertex) {
        Node parent = removal.getParentNode().orElse(null);
        while (parent instanceof Expression e && !(parent instanceof AssignExpr)
                && (e.isCastExpr() || e.isEnclosedExpr())) {
            parent = parent.getParentNode().orElse(null);
        }
        String holder = null;
        if (parent instanceof VariableDeclarator d) {
            holder = d.getNameAsString();
        } else if (parent instanceof AssignExpr a && a.getTarget().isNameExpr()) {
            holder = a.getTarget().asNameExpr().getNameAsString();
        }
        if (holder == null) {
            return null;
        }
        if (holder.equals(vertex)) {
            return "";
        }
        for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
            if (d.getNameAsString().equals(vertex) && d.getInitializer().isPresent()) {
                return accessorFrom(d.getInitializer().get(), holder);
            }
        }
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (isName(a.getTarget(), vertex)) {
                String found = accessorFrom(a.getValue(), holder);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String accessorFrom(Expression value, String holder) {
        Expression v = unwrap(value);
        if (v instanceof ArrayAccessExpr a && isName(a.getName(), holder) && a.getIndex().isIntegerLiteralExpr()) {
            return "[" + a.getIndex() + "]";
        }
        if (v instanceof FieldAccessExpr f && isName(f.getScope(), holder)) {
            return "." + f.getNameAsString();
        }
        return null;
    }

    private static boolean declaredAsPriorityQueue(CompilationUnit cu, String name) {
        String plain = name.startsWith("this.") ? name.substring(5) : name;
        for (VariableDeclarator d : cu.findAll(VariableDeclarator.class)) {
            if (d.getNameAsString().equals(plain)) {
                String declared = d.getType().asString() + " " + d.getInitializer().map(Node::toString).orElse("");
                if (declared.contains("PriorityQueue")) {
                    return true;
                }
            }
        }
        for (Parameter p : cu.findAll(Parameter.class)) {
            if (p.getNameAsString().equals(plain) && p.getType().asString().contains("PriorityQueue")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The next vertex is chosen by a scan for the smallest value, either in this method (outside the
     * neighbour loop) or in a helper it calls: {@code if (!done[v] && dist[v] < min) { min = dist[v]; best = v; }}.
     */
    private static boolean selectsMinimum(CallableDeclaration<?> m, CompilationUnit cu, Statement neighbourLoop) {
        List<Node> places = new ArrayList<>();
        places.add(m);
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) {
                for (MethodDeclaration helper : cu.findAll(MethodDeclaration.class)) {
                    if (helper.getNameAsString().equals(call.getNameAsString()) && helper != m) {
                        places.add(helper);
                    }
                }
            }
        }
        for (Node place : places) {
            for (ForStmt loop : place.findAll(ForStmt.class)) {
                if (loop == neighbourLoop || neighbourLoop.isAncestorOf(loop)) {
                    continue;
                }
                String index = forVariable(loop);
                if (index != null && tracksMinimum(loop.getBody(), index)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tracksMinimum(Statement body, String index) {
        for (IfStmt guard : body.findAll(IfStmt.class)) {
            boolean compares = guard.getCondition().findAll(BinaryExpr.class).stream().anyMatch(b ->
                    (b.getOperator() == BinaryExpr.Operator.LESS || b.getOperator() == BinaryExpr.Operator.LESS_EQUALS
                            || b.getOperator() == BinaryExpr.Operator.GREATER
                            || b.getOperator() == BinaryExpr.Operator.GREATER_EQUALS)
                            && (indexedBy(b.getLeft(), index) || indexedBy(b.getRight(), index)));
            boolean remembers = guard.getThenStmt().findAll(AssignExpr.class).stream()
                    .anyMatch(a -> isName(a.getValue(), index));
            if (compares && remembers) {
                return true;
            }
        }
        return false;
    }

    private static boolean indexedBy(Expression e, String index) {
        return unwrap(e) instanceof ArrayAccessExpr a && isName(a.getIndex(), index);
    }

    // ---------- helpers -----------------------------------------------------------------

    /** Conditions inside the body (if/while), plus the guard right around a call. */
    private static List<Expression> conditions(Statement body) {
        List<Expression> result = new ArrayList<>();
        body.findAll(IfStmt.class).forEach(s -> result.add(s.getCondition()));
        body.findAll(WhileStmt.class).forEach(s -> result.add(s.getCondition()));
        return result;
    }

    private static String elementKind(ForEachStmt loop) {
        return kindOfType(loop.getVariableDeclarator().getType().asString());
    }

    private static String kindOfType(String type) {
        if (type.endsWith("[]")) {
            return "array";
        }
        return switch (type) {
            case "int", "Integer", "long", "Long", "char", "Character", "String", "var" -> "vertex";
            default -> "object";
        };
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

    private static boolean isVariable(Expression e) {
        Expression x = unwrap(e);
        return x.isNameExpr() || x.isFieldAccessExpr() && x.asFieldAccessExpr().getScope().isThisExpr();
    }

    private static boolean isName(Expression e, String name) {
        Expression x = unwrap(e);
        return x.isNameExpr() && x.asNameExpr().getNameAsString().equals(name);
    }

    private static Expression unwrap(Expression e) {
        Expression x = e;
        while (x.isEnclosedExpr() || x.isCastExpr()) {
            x = x.isEnclosedExpr() ? x.asEnclosedExpr().getInner() : x.asCastExpr().getExpression();
        }
        return x;
    }

    private static String name(CallableDeclaration<?> m) {
        return m.isConstructorDeclaration() ? "<init>" : m.getNameAsString();
    }

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

    private static String text(Node n) {
        String raw = n.getTokenRange().map(Object::toString).orElseGet(n::toString);
        return raw.replaceAll("\\s+", "").strip();
    }
}
