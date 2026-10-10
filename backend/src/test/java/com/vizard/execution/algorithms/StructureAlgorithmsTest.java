package com.vizard.execution.algorithms;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tree and linked-list recognisers, each with look-alikes that must NOT be claimed. */
class StructureAlgorithmsTest {

    private static final String TREE_NODE = """
                static class Node {
                    int data;
                    Node left, right;
                    Node(int d) { data = d; }
                }
            """;

    private static final String LIST_NODE = """
                static class Node {
                    int data;
                    Node next;
                    Node(int d) { data = d; }
                }
            """;

    private static List<Algorithm> detect(String node, String methods) {
        return AlgorithmDetector.detect(StaticJavaParser.parse("class Main {\n" + node + methods + "\n}"))
                .stream().map(Detection::algorithm).toList();
    }

    @Test
    void recursiveBstDeleteIsRecognised() {
        assertThat(detect(TREE_NODE, """
                static Node delete(Node root, int key) {
                    if (root == null) return null;
                    if (key < root.data) {
                        root.left = delete(root.left, key);
                    } else if (key > root.data) {
                        root.right = delete(root.right, key);
                    } else {
                        if (root.left == null) return root.right;
                        if (root.right == null) return root.left;
                        Node s = root.right;
                        while (s.left != null) s = s.left;
                        root.data = s.data;
                        root.right = delete(root.right, s.data);
                    }
                    return root;
                }
                """)).containsExactly(Algorithm.BST_DELETE);
    }

    @Test
    void iterativeBstInsertAndRecursiveSearchAreRecognised() {
        assertThat(detect(TREE_NODE, """
                static Node insert(Node root, int key) {
                    Node fresh = new Node(key);
                    if (root == null) return fresh;
                    Node cur = root;
                    while (true) {
                        if (key < cur.data) {
                            if (cur.left == null) { cur.left = fresh; break; }
                            cur = cur.left;
                        } else {
                            if (cur.right == null) { cur.right = fresh; break; }
                            cur = cur.right;
                        }
                    }
                    return root;
                }
                static boolean contains(Node n, int key) {
                    if (n == null) return false;
                    if (key == n.data) return true;
                    return key < n.data ? contains(n.left, key) : contains(n.right, key);
                }
                """)).containsExactly(Algorithm.BST_INSERT, Algorithm.BST_SEARCH);
    }

    @Test
    void traversalOrderComesFromWhereTheNodeIsVisited() {
        assertThat(detect(TREE_NODE, """
                static void collect(Node n, java.util.List<Integer> out) {
                    if (n == null) return;
                    collect(n.left, out);
                    collect(n.right, out);
                    out.add(n.data);
                }
                """)).containsExactly(Algorithm.POSTORDER);
    }

    @Test
    void treeLookalikesAreNotClaimed() {
        assertThat(detect(TREE_NODE, """
                static int height(Node n) {
                    if (n == null) return -1;
                    return 1 + Math.max(height(n.left), height(n.right));
                }
                static int sum(Node n) {
                    return n == null ? 0 : n.data + sum(n.left) + sum(n.right);
                }
                static int count(Node n) {
                    if (n == null) return 0;
                    int c = 1;
                    c += count(n.left);
                    c += count(n.right);
                    return c;
                }
                static Node mirror(Node n) {
                    if (n == null) return null;
                    Node t = n.left;
                    n.left = mirror(n.right);
                    n.right = mirror(t);
                    return n;
                }
                """)).isEmpty();
    }

    @Test
    void iterativeAndRecursiveListReversalAreRecognised() {
        assertThat(detect(LIST_NODE, """
                static Node reverse(Node head) {
                    if (head == null || head.next == null) return head;
                    Node rest = reverse(head.next);
                    head.next.next = head;
                    head.next = null;
                    return rest;
                }
                """)).containsExactly(Algorithm.LIST_REVERSAL);
    }

    @Test
    void listLookalikesAreNotClaimed() {
        assertThat(detect(LIST_NODE, """
                static Node append(Node head, int value) {
                    Node fresh = new Node(value);
                    if (head == null) return fresh;
                    Node cur = head;
                    while (cur.next != null) cur = cur.next;
                    cur.next = fresh;
                    return head;
                }
                static Node insertSorted(Node head, int value) {
                    Node fresh = new Node(value);
                    Node prev = null, cur = head;
                    while (cur != null && cur.data < value) {
                        prev = cur;
                        cur = cur.next;
                    }
                    fresh.next = cur;
                    if (prev == null) return fresh;
                    prev.next = fresh;
                    return head;
                }
                static int length(Node head) {
                    int n = 0;
                    for (Node c = head; c != null; c = c.next) n++;
                    return n;
                }
                """)).isEmpty();
    }

    @Test
    void nodeClassesAreFoundByStructure() {
        var nodes = StructureAlgorithms.nodeClasses(StaticJavaParser.parse("""
                class Main {
                    static class TreeNode { int val; TreeNode left, right; }
                    static class Cell { String name; Cell next, prev; }
                    static class Point { int x, y; }
                }
                """));
        assertThat(nodes).containsOnlyKeys("TreeNode", "Cell");
        assertThat(nodes.get("TreeNode").tree()).isTrue();
        assertThat(nodes.get("TreeNode").valueField()).isEqualTo("val");
        assertThat(nodes.get("Cell").tree()).isFalse();
        assertThat(nodes.get("Cell").links()).containsExactly("next");
    }

    @Test
    void bstComplexityUsesTheMeasuredHeight() {
        Detection insert = new Detection(Algorithm.BST_INSERT, "insert", 1, List.of(), java.util.Set.of(Detection.RECURSIVE));
        var match = AlgorithmCatalog.describe(insert, null, new StructureFacts(7, 6, 0)); // a chain of 7
        assertThat(match.complexity().worst()).isEqualTo("O(n)");
        assertThat(match.complexity().space()).isEqualTo("O(h)");
        assertThat(match.measures()).extracting(m -> m.value()).containsExactly("7", "6", "2", "6");
    }
}
