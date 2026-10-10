package com.vizard.execution.algorithms;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recognisers for algorithms on the program's own linked structures: BST search, insertion and
 * deletion; preorder, inorder, postorder and level-order traversal; linked-list reversal.
 *
 * <p>Like the sorting recognisers they match structure, never names. They start from the
 * program's <b>node classes</b>, found the same way the trace classifies objects at runtime:
 * a class with fields {@code left} and {@code right} of its own type is a tree node; one with a
 * single link of its own type (plus an optional {@code prev}) is a list node.
 */
final class StructureAlgorithms {

    private static final Set<String> QUEUE_ADDS = Set.of("offer", "add", "addLast", "offerLast");
    private static final Set<String> QUEUE_REMOVES = Set.of("poll", "remove", "removeFirst", "pollFirst");

    /** A node class: its links (left, right / next, prev) and its first value field (data, key, val). */
    record NodeClass(String name, boolean tree, List<String> links, String valueField) {

        String first() {
            return links.get(0);
        }

        String second() {
            return links.size() > 1 ? links.get(1) : null;
        }
    }

    private StructureAlgorithms() {
    }

    static Map<String, NodeClass> nodeClasses(CompilationUnit cu) {
        Map<String, NodeClass> result = new LinkedHashMap<>();
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String name = c.getNameAsString();
            List<String> self = new ArrayList<>();
            String value = null;
            for (FieldDeclaration f : c.getFields()) {
                if (f.isStatic()) {
                    continue;
                }
                for (VariableDeclarator v : f.getVariables()) {
                    if (typeName(v.getType()).equals(name)) {
                        self.add(v.getNameAsString());
                    } else if (value == null) {
                        value = v.getNameAsString();
                    }
                }
            }
            String left = find(self, "left");
            String right = find(self, "right");
            List<String> forward = self.stream()
                    .filter(n -> !n.equalsIgnoreCase("prev") && !n.equalsIgnoreCase("previous")
                            && !n.equalsIgnoreCase("parent")).toList();
            if (left != null && right != null) {
                result.put(name, new NodeClass(name, true, List.of(left, right), value));
            } else if (forward.size() == 1) {
                result.put(name, new NodeClass(name, false, List.of(forward.get(0)), value));
            } else if (forward.size() == 2) {
                result.put(name, new NodeClass(name, true, List.copyOf(forward), value));
            }
        }
        return result;
    }

    static Optional<Detection> detect(MethodDeclaration m, Map<String, NodeClass> nodes) {
        if (nodes.isEmpty() || m.getBody().isEmpty()) {
            return Optional.empty();
        }
        return bstOperation(m, nodes)
                .or(() -> recursiveTraversal(m, nodes))
                .or(() -> levelOrder(m, nodes))
                .or(() -> listReversal(m, nodes));
    }

    // ---------- BST search / insert / delete --------------------------------------------

    /**
     * A key compared with a node's value field, then a move to the left or right child, both
     * ways: by recursion on node.left / node.right, or by walking c = c.left / c = c.right.
     * Creating a node makes it an insertion; returning node.left / node.right (splicing a node
     * out) makes it a deletion; otherwise it is a search.
     */
    private static Optional<Detection> bstOperation(MethodDeclaration m, Map<String, NodeClass> nodes) {
        for (NodeClass node : nodes.values()) {
            if (!node.tree() || node.valueField() == null) {
                continue;
            }
            Optional<BinaryExpr> keyCompare = m.findAll(BinaryExpr.class).stream()
                    .filter(StructureAlgorithms::isOrdering)
                    .filter(b -> readsField(b.getLeft(), node.valueField()) || readsField(b.getRight(), node.valueField()))
                    .findFirst();
            if (keyCompare.isEmpty()) {
                continue;
            }
            boolean recursive = selfCallWithFieldArg(m, node.first()) && selfCallWithFieldArg(m, node.second());
            boolean iterative = !m.findAll(WhileStmt.class).isEmpty()
                    && walksTo(m, node.first()) && walksTo(m, node.second());
            if (!recursive && !iterative) {
                continue;
            }
            boolean creates = m.findAll(ObjectCreationExpr.class).stream()
                    .anyMatch(o -> o.getType().getNameAsString().equals(node.name()));
            boolean splices = m.findAll(ReturnStmt.class).stream()
                    .anyMatch(r -> r.getExpression().map(e -> isField(e, node.first()) || isField(e, node.second()))
                            .orElse(false));

            List<String> evidence = new ArrayList<>();
            evidence.add("Compares the key with a node's " + node.valueField() + ": " + keyCompare.get()
                    + " (line " + line(keyCompare.get()) + ").");
            evidence.add(recursive
                    ? "Then calls itself on only one child, " + node.first() + " or " + node.second() + "."
                    : "Then walks down to one child, " + node.first() + " or " + node.second() + ", in a loop.");
            Algorithm algorithm;
            if (splices && recursive) {
                algorithm = Algorithm.BST_DELETE;
                evidence.add("Removes a node by returning its only child in its place (return node."
                        + node.first() + " / node." + node.second() + ").");
            } else if (creates) {
                algorithm = Algorithm.BST_INSERT;
                evidence.add("Creates a new " + node.name() + " where the search falls off the tree.");
            } else {
                algorithm = Algorithm.BST_SEARCH;
                evidence.add("Creates and removes nothing: it only looks.");
            }
            return Optional.of(detection(algorithm, m, evidence, recursive ? Set.of(Detection.RECURSIVE) : Set.of()));
        }
        return Optional.empty();
    }

    // ---------- traversals --------------------------------------------------------------

    /**
     * Calls itself on node.left and node.right, and does something with the node itself (prints
     * it, adds it to a list). Where that "visit" sits relative to the two calls decides the order.
     */
    private static Optional<Detection> recursiveTraversal(MethodDeclaration m, Map<String, NodeClass> nodes) {
        for (Parameter p : m.getParameters()) {
            NodeClass node = nodes.get(typeName(p.getType()));
            if (node == null || !node.tree()) {
                continue;
            }
            String param = p.getNameAsString();
            Optional<MethodCallExpr> leftCall = selfCall(m, param, node.first());
            Optional<MethodCallExpr> rightCall = selfCall(m, param, node.second());
            if (leftCall.isEmpty() || rightCall.isEmpty()) {
                continue;
            }
            Optional<ExpressionStmt> visit = m.findAll(ExpressionStmt.class).stream()
                    .filter(s -> s.findAll(MethodCallExpr.class).stream().noneMatch(c -> isSelfCall(m, c)))
                    .filter(s -> s.findAll(FieldAccessExpr.class).stream().anyMatch(f -> isFieldOf(f, param)
                            && !node.links().contains(f.getNameAsString())))
                    .findFirst();
            if (visit.isEmpty()) {
                continue;
            }
            int first = Math.min(position(leftCall.get()), position(rightCall.get()));
            int second = Math.max(position(leftCall.get()), position(rightCall.get()));
            int at = position(visit.get());
            Algorithm order = at < first ? Algorithm.PREORDER : at < second ? Algorithm.INORDER : Algorithm.POSTORDER;
            String where = switch (order) {
                case PREORDER -> "before both calls: node, then left, then right";
                case INORDER -> "between the two calls: left, then node, then right";
                default -> "after both calls: left, then right, then node";
            };
            return Optional.of(detection(order, m, List.of(
                    "Calls itself on " + param + "." + node.first() + " and " + param + "." + node.second()
                            + " (lines " + line(leftCall.get()) + " and " + line(rightCall.get()) + ").",
                    "Visits the node itself at line " + line(visit.get()) + ", " + where + "."),
                    Set.of(Detection.RECURSIVE)));
        }
        return Optional.empty();
    }

    /** A queue drained in a loop: take a node out, put its left and right children in. */
    private static Optional<Detection> levelOrder(MethodDeclaration m, Map<String, NodeClass> nodes) {
        for (WhileStmt loop : m.findAll(WhileStmt.class)) {
            for (NodeClass node : nodes.values()) {
                if (!node.tree()) {
                    continue;
                }
                Optional<MethodCallExpr> addLeft = queueAdd(loop, node.first());
                Optional<MethodCallExpr> addRight = queueAdd(loop, node.second());
                if (addLeft.isEmpty() || addRight.isEmpty()) {
                    continue;
                }
                String queue = addLeft.get().getScope().map(Expression::toString).orElse("");
                boolean drains = loop.findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> QUEUE_REMOVES.contains(c.getNameAsString()) && c.getArguments().isEmpty()
                                && c.getScope().map(Expression::toString).orElse("").equals(queue));
                if (drains) {
                    return Optional.of(detection(Algorithm.LEVEL_ORDER, m, List.of(
                            "A loop (line " + line(loop) + ") takes the next node out of the queue '" + queue + "'.",
                            "It puts that node's " + node.first() + " and " + node.second()
                                    + " children at the back, so nodes come out level by level."), Set.of()));
                }
            }
        }
        return Optional.empty();
    }

    // ---------- linked list reversal ----------------------------------------------------

    /**
     * Iterative: inside a loop, a.next = b and b = a (point the current node back at the
     * previous one, then move previous forward). Recursive: head.next.next = head.
     */
    private static Optional<Detection> listReversal(MethodDeclaration m, Map<String, NodeClass> nodes) {
        for (NodeClass node : nodes.values()) {
            if (node.tree()) {
                continue;
            }
            String link = node.first();
            for (WhileStmt loop : m.findAll(WhileStmt.class)) {
                List<AssignExpr> assigns = loop.findAll(AssignExpr.class);
                for (AssignExpr pointBack : assigns) {
                    if (!(pointBack.getTarget() instanceof FieldAccessExpr target) || !target.getNameAsString().equals(link)
                            || !(target.getScope() instanceof NameExpr a) || !(pointBack.getValue() instanceof NameExpr b)) {
                        continue;
                    }
                    boolean advances = assigns.stream().anyMatch(x -> x.getTarget() instanceof NameExpr t
                            && t.getNameAsString().equals(b.getNameAsString())
                            && x.getValue() instanceof NameExpr v && v.getNameAsString().equals(a.getNameAsString()));
                    if (advances) {
                        return Optional.of(detection(Algorithm.LIST_REVERSAL, m, List.of(
                                "A loop (line " + line(loop) + ") walks the list once.",
                                "Each node is turned around: " + pointBack + " (line " + line(pointBack) + ").",
                                "Then " + b + " moves forward to " + a + ", so no node is visited twice."), Set.of()));
                    }
                }
            }
            for (AssignExpr a : m.findAll(AssignExpr.class)) {
                if (a.getTarget() instanceof FieldAccessExpr outer && outer.getNameAsString().equals(link)
                        && outer.getScope() instanceof FieldAccessExpr inner && inner.getNameAsString().equals(link)
                        && inner.getScope() instanceof NameExpr head && a.getValue() instanceof NameExpr back
                        && back.getNameAsString().equals(head.getNameAsString()) && hasSelfCall(m)) {
                    return Optional.of(detection(Algorithm.LIST_REVERSAL, m, List.of(
                            name(m) + " first reverses the rest of the list by calling itself.",
                            "Then points the next node back at this one: " + a + " (line " + line(a) + ")."),
                            Set.of(Detection.RECURSIVE)));
                }
            }
        }
        return Optional.empty();
    }

    // ---------- helpers -----------------------------------------------------------------

    private static boolean isOrdering(BinaryExpr b) {
        return switch (b.getOperator()) {
            case LESS, GREATER, LESS_EQUALS, GREATER_EQUALS -> true;
            default -> false;
        };
    }

    /** x.field, or x.field.compareTo(...) */
    private static boolean readsField(Expression e, String field) {
        if (isField(e, field)) {
            return true;
        }
        return e instanceof MethodCallExpr call && call.getNameAsString().equals("compareTo")
                && (call.getScope().map(s -> isField(s, field)).orElse(false)
                || call.getArguments().stream().anyMatch(a -> isField(a, field)));
    }

    private static boolean isField(Expression e, String field) {
        return e instanceof FieldAccessExpr f && f.getNameAsString().equals(field);
    }

    private static boolean isFieldOf(FieldAccessExpr f, String owner) {
        return f.getScope() instanceof NameExpr n && n.getNameAsString().equals(owner);
    }

    private static boolean isSelfCall(MethodDeclaration m, MethodCallExpr c) {
        return c.getNameAsString().equals(m.getNameAsString())
                && (c.getScope().isEmpty() || c.getScope().get().isThisExpr());
    }

    private static boolean hasSelfCall(MethodDeclaration m) {
        return m.findAll(MethodCallExpr.class).stream().anyMatch(c -> isSelfCall(m, c));
    }

    /** A recursive call with an argument like root.left. */
    private static boolean selfCallWithFieldArg(MethodDeclaration m, String field) {
        return field != null && m.findAll(MethodCallExpr.class).stream()
                .anyMatch(c -> isSelfCall(m, c) && c.getArguments().stream().anyMatch(a -> isField(a, field)));
    }

    /** The recursive call whose argument is param.field. */
    private static Optional<MethodCallExpr> selfCall(MethodDeclaration m, String param, String field) {
        return m.findAll(MethodCallExpr.class).stream()
                .filter(c -> isSelfCall(m, c))
                .filter(c -> c.getArguments().stream()
                        .anyMatch(a -> a instanceof FieldAccessExpr f && f.getNameAsString().equals(field) && isFieldOf(f, param)))
                .findFirst();
    }

    /** cur = cur.left (or = parent.left etc.) */
    private static boolean walksTo(MethodDeclaration m, String field) {
        return field != null && m.findAll(AssignExpr.class).stream()
                .anyMatch(a -> a.getTarget() instanceof NameExpr && isField(a.getValue(), field));
    }

    private static Optional<MethodCallExpr> queueAdd(WhileStmt loop, String field) {
        return loop.findAll(MethodCallExpr.class).stream()
                .filter(c -> QUEUE_ADDS.contains(c.getNameAsString()) && c.getScope().isPresent())
                .filter(c -> c.getArguments().size() == 1 && isField(c.getArgument(0), field))
                .findFirst();
    }

    private static String typeName(Type t) {
        return t instanceof ClassOrInterfaceType c ? c.getNameAsString() : t.asString();
    }

    private static String find(List<String> names, String wanted) {
        return names.stream().filter(n -> n.equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    private static int position(Node n) {
        return n.getBegin().map(p -> p.line * 10_000 + p.column).orElse(0);
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static String name(MethodDeclaration m) {
        return m.getNameAsString() + "()";
    }

    private static Detection detection(Algorithm algorithm, MethodDeclaration m, List<String> evidence,
                                       Set<String> traits) {
        return new Detection(algorithm, m.getNameAsString(), line(m), List.copyOf(evidence), Set.copyOf(traits));
    }
}
