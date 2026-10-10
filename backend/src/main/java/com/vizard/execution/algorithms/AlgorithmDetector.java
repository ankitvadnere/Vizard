package com.vizard.execution.algorithms;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises well-known algorithms by the <b>structure</b> of the code, not by method names.
 *
 * <p>Each recogniser looks for the defining pattern of one algorithm, e.g. bubble sort =
 * two nested loops + an if comparing neighbouring elements {@code a[j]} and {@code a[j + 1]}
 * + a swap inside that if. A method is reported as at most one algorithm. Code that matches
 * nothing gets no complexity claim: Vizard does not pretend to analyse arbitrary code.
 */
public final class AlgorithmDetector {

    private AlgorithmDetector() {
    }

    public static List<Detection> detect(CompilationUnit cu) {
        Map<String, MethodDeclaration> methodsByName = new HashMap<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            methodsByName.putIfAbsent(m.getNameAsString(), m);
        }

        Map<String, StructureAlgorithms.NodeClass> nodeClasses = StructureAlgorithms.nodeClasses(cu);
        List<Detection> found = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            if (m.getBody().isEmpty()) {
                continue;
            }
            Optional<Detection> d = StructureAlgorithms.detect(m, nodeClasses)
                    .or(() -> mergeSort(m, methodsByName))
                    .or(() -> quickSort(m, methodsByName))
                    .or(() -> binarySearch(m))
                    .or(() -> bubbleSort(m))
                    .or(() -> selectionSort(m))
                    .or(() -> insertionSort(m))
                    .or(() -> linearSearch(m));
            d.ifPresent(found::add);
        }
        found.removeIf(d -> isHelperOfUnknownRecursion(d, cu, found));
        found.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return found;
    }

    /**
     * A recognised method called by a recursive method that is not recognised itself, e.g. the
     * isSafe() scan inside N-Queens backtracking. Its complexity says nothing true about the
     * program (the recursion dominates), so it is not reported on its own.
     */
    private static boolean isHelperOfUnknownRecursion(Detection d, CompilationUnit cu, List<Detection> found) {
        for (MethodDeclaration caller : cu.findAll(MethodDeclaration.class)) {
            if (caller.getNameAsString().equals(d.method()) || selfCalls(caller) == 0) {
                continue;
            }
            boolean callsIt = caller.findAll(MethodCallExpr.class).stream()
                    .anyMatch(c -> c.getNameAsString().equals(d.method()));
            boolean callerRecognised = found.stream().anyMatch(f -> f.method().equals(caller.getNameAsString()));
            if (callsIt && !callerRecognised) {
                return true;
            }
        }
        return false;
    }

    // ---------- sorting ------------------------------------------------------------------

    private static Optional<Detection> bubbleSort(MethodDeclaration m) {
        for (IfStmt ifStmt : m.findAll(IfStmt.class)) {
            if (loopDepth(ifStmt, m) < 2 || !hasSwap(ifStmt.getThenStmt())) {
                continue;
            }
            for (BinaryExpr c : relational(ifStmt.getCondition())) {
                if (!(unwrap(c.getLeft()) instanceof ArrayAccessExpr left)
                        || !(unwrap(c.getRight()) instanceof ArrayAccessExpr right)
                        || !sameArray(left, right) || !adjacent(left.getIndex(), right.getIndex())) {
                    continue;
                }
                Set<String> traits = new LinkedHashSet<>();
                List<String> evidence = new ArrayList<>();
                evidence.add("Two nested loops in " + name(m) + ".");
                evidence.add("Compares neighbouring elements " + left + " and " + right + " (line " + line(c) + ").");
                evidence.add("Swaps them when they are out of order, so large values bubble to the end.");
                if (hasEarlyExit(m)) {
                    traits.add(Detection.EARLY_EXIT);
                    evidence.add("Has an early exit (a flag or break) for when a pass makes no swaps.");
                }
                if (innerLoopShrinks(ifStmt)) {
                    traits.add(Detection.SHRINKING);
                } else {
                    evidence.add("The inner loop doesn't shrink after each pass, so it re-checks elements already "
                            + "in place (still O(n²)).");
                }
                return Optional.of(detection(Algorithm.BUBBLE_SORT, m, evidence, traits));
            }
        }
        return Optional.empty();
    }

    private static Optional<Detection> selectionSort(MethodDeclaration m) {
        for (IfStmt ifStmt : m.findAll(IfStmt.class)) {
            if (loopDepth(ifStmt, m) < 2 || hasSwap(ifStmt.getThenStmt())) {
                continue;
            }
            for (BinaryExpr c : relational(ifStmt.getCondition())) {
                if (!(unwrap(c.getLeft()) instanceof ArrayAccessExpr left)
                        || !(unwrap(c.getRight()) instanceof ArrayAccessExpr right) || !sameArray(left, right)) {
                    continue;
                }
                for (ArrayAccessExpr side : List.of(left, right)) {
                    if (!(side.getIndex() instanceof NameExpr best)) {
                        continue;
                    }
                    String bestName = best.getNameAsString();
                    boolean remembersIndex = ifStmt.getThenStmt().findAll(AssignExpr.class).stream()
                            .anyMatch(a -> a.getTarget().isNameExpr()
                                    && a.getTarget().asNameExpr().getNameAsString().equals(bestName));
                    if (remembersIndex && hasSwap(m.getBody().get())) {
                        return Optional.of(detection(Algorithm.SELECTION_SORT, m, List.of(
                                "Two nested loops in " + name(m) + ".",
                                "The inner loop remembers the position of the smallest (or largest) element in '"
                                        + bestName + "' (line " + line(ifStmt) + ").",
                                "That element is swapped into place once per pass of the outer loop."),
                                Set.of()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Detection> insertionSort(MethodDeclaration m) {
        for (Statement loop : loops(m)) {
            Optional<Expression> condition = loopCondition(loop);
            if (condition.isEmpty() || loopDepth(loop, m) < 1) {
                continue;
            }
            boolean comparesElement = relational(condition.get()).stream()
                    .anyMatch(c -> unwrap(c.getLeft()) instanceof ArrayAccessExpr
                            || unwrap(c.getRight()) instanceof ArrayAccessExpr);
            if (comparesElement && hasMove(loopBody(loop))) {
                return Optional.of(detection(Algorithm.INSERTION_SORT, m, List.of(
                        "An outer loop takes the next element in " + name(m) + ".",
                        "An inner loop (line " + line(loop) + ") shifts larger elements one place right "
                                + "while the comparison holds.",
                        "The element is then inserted into the gap, growing a sorted prefix."), Set.of()));
            }
        }
        return Optional.empty();
    }

    private static Optional<Detection> mergeSort(MethodDeclaration m, Map<String, MethodDeclaration> methods) {
        if (selfCalls(m) < 2 || midVariable(m).isEmpty()) {
            return Optional.empty();
        }
        Optional<MethodDeclaration> merge = isMergeStep(m) ? Optional.of(m)
                : calledMethods(m, methods).stream().filter(AlgorithmDetector::isMergeStep).findFirst();
        return merge.map(mergeMethod -> detection(Algorithm.MERGE_SORT, m, List.of(
                name(m) + " splits the range at the middle ('" + midVariable(m).get() + "') and calls itself on "
                        + "both halves.",
                (mergeMethod == m ? name(m) : name(mergeMethod))
                        + " merges two sorted halves by repeatedly comparing their front elements."), Set.of()));
    }

    private static Optional<Detection> quickSort(MethodDeclaration m, Map<String, MethodDeclaration> methods) {
        if (selfCalls(m) < 2) {
            return Optional.empty();
        }
        Optional<MethodDeclaration> partition = isPartition(m) ? Optional.of(m)
                : calledMethods(m, methods).stream().filter(AlgorithmDetector::isPartition).findFirst();
        return partition.map(p -> detection(Algorithm.QUICK_SORT, m, List.of(
                name(p) + " picks a pivot element and moves smaller elements before it (partitioning).",
                name(m) + " then calls itself on the parts before and after the pivot."), Set.of()));
    }

    /** A loop with an if comparing two array elements that writes an element: the merge step. */
    private static boolean isMergeStep(MethodDeclaration m) {
        for (IfStmt ifStmt : m.findAll(IfStmt.class)) {
            if (loopDepth(ifStmt, m) != 1) {
                continue;
            }
            boolean comparesTwoElements = relational(ifStmt.getCondition()).stream()
                    .anyMatch(c -> unwrap(c.getLeft()) instanceof ArrayAccessExpr
                            && unwrap(c.getRight()) instanceof ArrayAccessExpr);
            boolean writesElement = ifStmt.getThenStmt().findAll(AssignExpr.class).stream()
                    .anyMatch(a -> a.getTarget() instanceof ArrayAccessExpr);
            if (comparesTwoElements && writesElement) {
                return true;
            }
        }
        return false;
    }

    /** pivot = a[...]; a loop compares elements with pivot; elements are swapped. */
    private static boolean isPartition(MethodDeclaration m) {
        Set<String> pivots = new HashSet<>();
        for (VariableDeclarator v : m.findAll(VariableDeclarator.class)) {
            if (v.getInitializer().map(i -> unwrap(i) instanceof ArrayAccessExpr).orElse(false)) {
                pivots.add(v.getNameAsString());
            }
        }
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getTarget().isNameExpr() && unwrap(a.getValue()) instanceof ArrayAccessExpr) {
                pivots.add(a.getTarget().asNameExpr().getNameAsString());
            }
        }
        if (pivots.isEmpty() || !hasSwap(m.getBody().get())) {
            return false;
        }
        List<Expression> conditions = new ArrayList<>();
        m.findAll(IfStmt.class).stream().filter(i -> loopDepth(i, m) >= 1).forEach(i -> conditions.add(i.getCondition()));
        for (Statement loop : loops(m)) {
            loopCondition(loop).ifPresent(conditions::add);
        }
        for (Expression condition : conditions) {
            for (BinaryExpr c : relational(condition)) {
                Expression l = unwrap(c.getLeft());
                Expression r = unwrap(c.getRight());
                boolean elementVsPivot = (l instanceof ArrayAccessExpr && isNameIn(r, pivots))
                        || (r instanceof ArrayAccessExpr && isNameIn(l, pivots));
                if (elementVsPivot) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- searching ----------------------------------------------------------------

    private static Optional<Detection> binarySearch(MethodDeclaration m) {
        Optional<String> mid = midVariable(m);
        if (mid.isEmpty()) {
            return Optional.empty();
        }
        String midName = mid.get();
        boolean comparesMiddle = m.findAll(IfStmt.class).stream()
                .flatMap(i -> relational(i.getCondition()).stream())
                .anyMatch(c -> isElementAt(c.getLeft(), midName) || isElementAt(c.getRight(), midName));
        if (!comparesMiddle) {
            return Optional.empty();
        }
        Optional<WhileStmt> rangeLoop = m.findAll(WhileStmt.class).stream()
                .filter(w -> relational(w.getCondition()).stream()
                        .anyMatch(c -> unwrap(c.getLeft()).isNameExpr() && unwrap(c.getRight()).isNameExpr()))
                .findFirst();
        if (rangeLoop.isPresent()) {
            return Optional.of(detection(Algorithm.BINARY_SEARCH, m, List.of(
                    "A loop runs while the range is not empty: " + rangeLoop.get().getCondition() + " (line "
                            + line(rangeLoop.get()) + ").",
                    "Each pass looks at the middle element '" + midName + "' and discards the half that can't "
                            + "contain the target."), Set.of()));
        }
        if (selfCalls(m) >= 1) {
            return Optional.of(detection(Algorithm.BINARY_SEARCH, m, List.of(
                    name(m) + " compares the target with the middle element '" + midName + "'.",
                    "It then calls itself on only one half of the range."), Set.of(Detection.RECURSIVE)));
        }
        return Optional.empty();
    }

    private static Optional<Detection> linearSearch(MethodDeclaration m) {
        for (Statement loop : loops(m)) {
            if (loopDepth(loop, m) != 0) {
                continue;
            }
            Set<String> loopVariables = new HashSet<>();
            if (loop instanceof ForStmt f) {
                f.getInitialization().stream().filter(e -> e instanceof VariableDeclarationExpr)
                        .flatMap(e -> ((VariableDeclarationExpr) e).getVariables().stream())
                        .forEach(v -> loopVariables.add(v.getNameAsString()));
            } else if (loop instanceof ForEachStmt f) {
                loopVariables.add(f.getVariableDeclarator().getNameAsString());
            } else {
                continue;
            }
            for (IfStmt ifStmt : loopBody(loop).findAll(IfStmt.class)) {
                boolean stops = !ifStmt.getThenStmt().findAll(ReturnStmt.class).isEmpty()
                        || !ifStmt.getThenStmt().findAll(BreakStmt.class).isEmpty()
                        || ifStmt.getThenStmt() instanceof ReturnStmt || ifStmt.getThenStmt() instanceof BreakStmt;
                if (!stops || returnsFalse(ifStmt.getThenStmt())) {
                    continue; // "return false" on a match is a validity check (isSafe), not a search
                }
                for (BinaryExpr c : relational(ifStmt.getCondition())) {
                    if (c.getOperator() != BinaryExpr.Operator.EQUALS) {
                        continue;
                    }
                    boolean checksCurrent = loop instanceof ForEachStmt
                            ? isNameIn(unwrap(c.getLeft()), loopVariables) || isNameIn(unwrap(c.getRight()), loopVariables)
                            : isElementIndexedBy(c.getLeft(), loopVariables) || isElementIndexedBy(c.getRight(), loopVariables);
                    if (checksCurrent) {
                        return Optional.of(detection(Algorithm.LINEAR_SEARCH, m, List.of(
                                "One loop visits the elements in order (line " + line(loop) + ").",
                                "Each element is compared with the target: " + c + ".",
                                "It stops as soon as the target is found."), Set.of()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean returnsFalse(Statement branch) {
        List<ReturnStmt> returns = new ArrayList<>(branch.findAll(ReturnStmt.class));
        if (branch instanceof ReturnStmt r) {
            returns.add(r);
        }
        return returns.stream().anyMatch(r -> r.getExpression()
                .map(e -> e.isBooleanLiteralExpr() && !e.asBooleanLiteralExpr().getValue())
                .orElse(false));
    }

    // ---------- structural helpers ------------------------------------------------------

    private static Detection detection(Algorithm algorithm, MethodDeclaration m, List<String> evidence,
                                       Set<String> traits) {
        return new Detection(algorithm, m.getNameAsString(), line(m), List.copyOf(evidence), Set.copyOf(traits));
    }

    private static boolean isLoop(Node n) {
        return n instanceof ForStmt || n instanceof WhileStmt || n instanceof DoStmt || n instanceof ForEachStmt;
    }

    private static List<Statement> loops(Node scope) {
        List<Statement> result = new ArrayList<>();
        scope.walk(Node.TreeTraversal.PREORDER, n -> {
            if (isLoop(n)) {
                result.add((Statement) n);
            }
        });
        return result;
    }

    /** How many loops of the method enclose this node. */
    private static int loopDepth(Node node, Node method) {
        int depth = 0;
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != method) {
            if (isLoop(current)) {
                depth++;
            }
            current = current.getParentNode().orElse(null);
        }
        return depth;
    }

    private static Optional<Expression> loopCondition(Statement loop) {
        if (loop instanceof WhileStmt w) return Optional.of(w.getCondition());
        if (loop instanceof DoStmt d) return Optional.of(d.getCondition());
        if (loop instanceof ForStmt f) return f.getCompare();
        return Optional.empty();
    }

    private static Statement loopBody(Statement loop) {
        if (loop instanceof WhileStmt w) return w.getBody();
        if (loop instanceof DoStmt d) return d.getBody();
        if (loop instanceof ForStmt f) return f.getBody();
        return ((ForEachStmt) loop).getBody();
    }

    /** All <, >, <=, >=, ==, != comparisons in an expression (including inside && and ||). */
    private static List<BinaryExpr> relational(Expression e) {
        List<BinaryExpr> result = new ArrayList<>();
        e.walk(Node.TreeTraversal.PREORDER, n -> {
            if (n instanceof BinaryExpr b) {
                switch (b.getOperator()) {
                    case LESS, GREATER, LESS_EQUALS, GREATER_EQUALS, EQUALS, NOT_EQUALS -> result.add(b);
                    default -> {
                    }
                }
            }
        });
        return result;
    }

    private static Expression unwrap(Expression e) {
        while (e instanceof EnclosedExpr enclosed) {
            e = enclosed.getInner();
        }
        return e;
    }

    private static boolean sameArray(ArrayAccessExpr a, ArrayAccessExpr b) {
        return a.getName().toString().equals(b.getName().toString());
    }

    /** {@code j} and {@code j + 1} (either order), or {@code j} and {@code j - 1}. */
    private static boolean adjacent(Expression a, Expression b) {
        return isNeighbour(unwrap(a), unwrap(b)) || isNeighbour(unwrap(b), unwrap(a));
    }

    private static boolean isNeighbour(Expression base, Expression other) {
        if (!(other instanceof BinaryExpr b)) {
            return false;
        }
        boolean plusOrMinus = b.getOperator() == BinaryExpr.Operator.PLUS || b.getOperator() == BinaryExpr.Operator.MINUS;
        return plusOrMinus && b.getLeft().toString().equals(base.toString())
                && b.getRight() instanceof IntegerLiteralExpr one && one.getValue().equals("1");
    }

    /** {@code a[x] = a[y]}: an element moved within one array (part of a swap or a shift). */
    private static boolean hasMove(Node scope) {
        for (AssignExpr a : scope.findAll(AssignExpr.class)) {
            if (a.getOperator() == AssignExpr.Operator.ASSIGN
                    && a.getTarget() instanceof ArrayAccessExpr target
                    && unwrap(a.getValue()) instanceof ArrayAccessExpr value
                    && sameArray(target, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSwap(Node scope) {
        return hasMove(scope) || scope.findAll(MethodCallExpr.class).stream()
                .anyMatch(c -> c.getNameAsString().equalsIgnoreCase("swap"));
    }

    /** A break, or a loop condition that tests a boolean flag such as 'swapped'. */
    private static boolean hasEarlyExit(MethodDeclaration m) {
        if (!m.findAll(BreakStmt.class).isEmpty()) {
            return true;
        }
        Set<String> flags = new HashSet<>();
        for (VariableDeclarator v : m.findAll(VariableDeclarator.class)) {
            if (v.getType().asString().equals("boolean")) {
                flags.add(v.getNameAsString());
            }
        }
        for (Statement loop : loops(m)) {
            Optional<Expression> condition = loopCondition(loop);
            if (condition.isPresent() && condition.get().findAll(NameExpr.class).stream()
                    .anyMatch(n -> flags.contains(n.getNameAsString()))) {
                return true;
            }
            if (condition.isPresent() && condition.get() instanceof NameExpr n && flags.contains(n.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The inner loop covers less each pass: its bound or start uses something the outer loop
     * changes, like {@code j < n - i - 1} (outer i++), {@code j = i + 1}, or {@code j < n} with n-- outside.
     */
    private static boolean innerLoopShrinks(IfStmt ifStmt) {
        Optional<ForStmt> inner = ifStmt.findAncestor(ForStmt.class);
        if (inner.isEmpty()) {
            return false;
        }
        Node outer = inner.get().getParentNode().orElse(null);
        while (outer != null && !isLoop(outer)) {
            outer = outer.getParentNode().orElse(null);
        }
        if (outer == null) {
            return false;
        }
        Set<String> changedByOuter = modifiedNames(outer);
        changedByOuter.removeAll(modifiedNames(inner.get())); // e.g. the inner j itself

        List<Expression> innerBounds = new ArrayList<>(inner.get().getInitialization());
        inner.get().getCompare().ifPresent(innerBounds::add);
        return innerBounds.stream().flatMap(e -> e.findAll(NameExpr.class).stream())
                .anyMatch(n -> changedByOuter.contains(n.getNameAsString()));
    }

    /** Names assigned, incremented or decremented anywhere inside a node (including a for-loop's own update). */
    private static Set<String> modifiedNames(Node scope) {
        Set<String> names = new HashSet<>();
        for (AssignExpr a : scope.findAll(AssignExpr.class)) {
            if (a.getTarget().isNameExpr()) {
                names.add(a.getTarget().asNameExpr().getNameAsString());
            }
        }
        for (UnaryExpr u : scope.findAll(UnaryExpr.class)) {
            boolean step = u.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
                    || u.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
                    || u.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || u.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT;
            if (step && u.getExpression().isNameExpr()) {
                names.add(u.getExpression().asNameExpr().getNameAsString());
            }
        }
        if (scope instanceof ForStmt f) {
            for (Expression init : f.getInitialization()) {
                if (init instanceof VariableDeclarationExpr decl) {
                    decl.getVariables().forEach(v -> names.add(v.getNameAsString()));
                }
            }
        }
        return names;
    }

    /** A variable set to something like (low + high) / 2 or low + (high - low) / 2. */
    private static Optional<String> midVariable(MethodDeclaration m) {
        for (VariableDeclarator v : m.findAll(VariableDeclarator.class)) {
            if (v.getInitializer().map(AlgorithmDetector::halves).orElse(false)) {
                return Optional.of(v.getNameAsString());
            }
        }
        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getTarget().isNameExpr() && halves(a.getValue())) {
                return Optional.of(a.getTarget().asNameExpr().getNameAsString());
            }
        }
        return Optional.empty();
    }

    private static boolean halves(Expression e) {
        Expression value = unwrap(e);
        if (!(value instanceof BinaryExpr)) {
            return false; // e.g. arr[(lo + hi) / 2] is an element, not a midpoint
        }
        List<BinaryExpr> parts = new ArrayList<>(value.findAll(BinaryExpr.class));
        boolean byTwo = parts.stream().anyMatch(b ->
                (b.getOperator() == BinaryExpr.Operator.DIVIDE && b.getRight().toString().equals("2"))
                        || ((b.getOperator() == BinaryExpr.Operator.SIGNED_RIGHT_SHIFT
                        || b.getOperator() == BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT) && b.getRight().toString().equals("1")));
        long names = value.findAll(NameExpr.class).stream().map(NameExpr::getNameAsString).distinct().count();
        return byTwo && names >= 2;
    }

    private static int selfCalls(MethodDeclaration m) {
        int count = 0;
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            boolean unqualified = call.getScope().isEmpty() || call.getScope().get().isThisExpr();
            if (unqualified && call.getNameAsString().equals(m.getNameAsString())
                    && call.getArguments().size() == m.getParameters().size()) {
                count++;
            }
        }
        return count;
    }

    private static List<MethodDeclaration> calledMethods(MethodDeclaration m, Map<String, MethodDeclaration> methods) {
        List<MethodDeclaration> result = new ArrayList<>();
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            MethodDeclaration target = methods.get(call.getNameAsString());
            if (target != null && target != m && !result.contains(target)) {
                result.add(target);
            }
        }
        return result;
    }

    private static boolean isElementAt(Expression e, String indexName) {
        return unwrap(e) instanceof ArrayAccessExpr a && unwrap(a.getIndex()) instanceof NameExpr n
                && n.getNameAsString().equals(indexName);
    }

    private static boolean isElementIndexedBy(Expression e, Set<String> indexNames) {
        return unwrap(e) instanceof ArrayAccessExpr a && unwrap(a.getIndex()) instanceof NameExpr n
                && indexNames.contains(n.getNameAsString());
    }

    private static boolean isNameIn(Expression e, Set<String> names) {
        return e instanceof NameExpr n && names.contains(n.getNameAsString());
    }

    private static String name(MethodDeclaration m) {
        return m.getNameAsString() + "()";
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }
}
