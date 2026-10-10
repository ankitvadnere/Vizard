package com.vizard.execution.algorithms;

import java.util.Set;

/**
 * Cost of one call on a standard collection, from the JDK documentation of each class.
 * Like the algorithm catalogue this is a known mapping, applied only to calls the run made.
 */
public final class StructureCosts {

    private static final Set<String> HASHED = Set.of("HashMap", "LinkedHashMap", "HashSet", "LinkedHashSet");
    private static final Set<String> SORTED = Set.of("TreeMap", "TreeSet");
    private static final Set<String> ARRAY_BACKED = Set.of("ArrayList", "Vector", "Stack", "List");

    private StructureCosts() {
    }

    /** @param argCount distinguishes remove() from remove(x), add(x) from add(i, x) */
    public static String cost(String type, String method, int argCount) {
        if (method.equals("clear") || method.equals("containsValue")) {
            return "O(n)";
        }
        if (HASHED.contains(type)) {
            return "O(1) average";
        }
        if (SORTED.contains(type)) {
            return "O(log n)";
        }
        if (type.equals("PriorityQueue")) {
            return switch (method) {
                case "peek", "element" -> "O(1)";
                case "contains" -> "O(n)";
                case "remove" -> argCount == 0 ? "O(log n)" : "O(n)";
                default -> "O(log n)"; // offer, add, poll: sift up / sift down
            };
        }
        if (type.equals("ArrayDeque")) {
            return switch (method) {
                case "contains", "remove" -> argCount == 0 ? "O(1)" : "O(n)";
                case "push", "add", "addFirst", "addLast", "offer", "offerFirst", "offerLast" -> "O(1) amortized";
                default -> "O(1)";
            };
        }
        if (type.equals("LinkedList")) {
            return switch (method) {
                case "get", "set", "indexOf", "contains" -> "O(n)";
                case "add" -> argCount == 2 ? "O(n)" : "O(1)";
                case "remove" -> argCount == 0 ? "O(1)" : "O(n)";
                default -> "O(1)";
            };
        }
        if (ARRAY_BACKED.contains(type)) {
            return switch (method) {
                case "get", "set", "peek", "pop", "getFirst", "getLast", "firstElement", "lastElement" -> "O(1)";
                case "add", "push" -> argCount == 2 ? "O(n)" : "O(1) amortized";
                default -> "O(n)"; // remove(i), remove(x), indexOf, contains, search
            };
        }
        return "?";
    }

    public static String note(String type) {
        if (HASHED.contains(type)) {
            return "Hashing finds a bucket directly: O(1) on average. Many keys in one bucket make it slower "
                    + "(Java switches a crowded bucket to a small tree, so O(log n) at worst for comparable keys).";
        }
        if (SORTED.contains(type)) {
            return "A red-black tree, always balanced: every lookup and update is O(log n), and keys stay sorted.";
        }
        return switch (type) {
            case "PriorityQueue" -> "A binary heap in an array: the smallest element is always at the root. "
                    + "Adding or removing moves an element up or down one path, O(log n).";
            case "ArrayDeque" -> "A circular array with a head and a tail: both ends work in O(1). It grows by "
                    + "copying when full, so adding is O(1) amortized.";
            case "LinkedList" -> "Doubly linked nodes: O(1) at either end, but reaching position i walks i nodes, O(n).";
            case "Stack" -> "A Vector (growable array) whose last element is the top: push and pop are O(1).";
            default -> "A growable array: index access is O(1); adding at the end is O(1) amortized; "
                    + "inserting or removing in the middle shifts elements, O(n).";
        };
    }
}
