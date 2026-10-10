package com.vizard.execution.insight;

import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.StructureOperation;
import com.vizard.api.dto.trace.TraceStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides how each collection is <b>used</b>, from the calls the program actually made on it,
 * and stamps that role into every step: an ArrayDeque that only sees push/pop is drawn as a
 * stack; the same class fed with offer/poll is a queue. Each operation also gets its meaning
 * ("push", "dequeue", "lookup"...) for the statistics and the step description.
 */
public final class StructureRoles {

    private StructureRoles() {
    }

    private record Role(String role, String top) {
    }

    public static List<TraceStep> apply(List<TraceStep> steps) {
        Map<Long, Set<String>> used = new HashMap<>();
        Map<Long, String> types = new HashMap<>();
        for (TraceStep step : steps) {
            for (StructureOperation op : step.operations()) {
                used.computeIfAbsent(op.ref(), k -> new HashSet<>()).add(signature(op));
                types.put(op.ref(), op.type());
            }
            for (HeapObjectSnapshot o : step.heap().values()) {
                if ("collection".equals(o.kind())) {
                    types.putIfAbsent(o.id(), o.type());
                }
            }
        }
        Map<Long, Role> roles = new HashMap<>();
        for (Map.Entry<Long, String> e : types.entrySet()) {
            roles.put(e.getKey(), decide(e.getValue(), used.getOrDefault(e.getKey(), Set.of())));
        }

        List<TraceStep> out = new ArrayList<>(steps.size());
        for (TraceStep step : steps) {
            Map<String, HeapObjectSnapshot> heap = new LinkedHashMap<>();
            for (Map.Entry<String, HeapObjectSnapshot> e : step.heap().entrySet()) {
                HeapObjectSnapshot o = e.getValue();
                Role role = "collection".equals(o.kind()) ? roles.get(o.id()) : null;
                heap.put(e.getKey(), role == null ? o : o.withRole(role.role(), role.top()));
            }
            List<StructureOperation> ops = new ArrayList<>(step.operations().size());
            for (StructureOperation op : step.operations()) {
                Role role = roles.get(op.ref());
                ops.add(op.withKind(kind(role == null ? "list" : role.role(), op)));
            }
            out.add(step.withStructures(heap, List.copyOf(ops)));
        }
        return out;
    }

    /** "remove" with no argument removes the head; with one it removes that element: tell them apart. */
    private static String signature(StructureOperation op) {
        return op.method().equals("remove") && op.args().isEmpty() ? "remove()" : op.method();
    }

    static Role decide(String type, Set<String> used) {
        switch (type) {
            case "Stack":
                return new Role("stack", "last");
            case "PriorityQueue":
                return new Role("priority-queue", null);
            case "HashMap", "LinkedHashMap", "TreeMap":
                return new Role("map", null);
            case "HashSet", "LinkedHashSet", "TreeSet":
                return new Role("set", null);
            case "ArrayDeque", "LinkedList":
                break;
            default:
                return new Role("list", null);
        }
        boolean stackOps = used.contains("push") || used.contains("pop");
        boolean frontInsert = used.contains("addFirst") || used.contains("offerFirst");
        boolean backInsert = used.contains("add") || used.contains("addLast") || used.contains("offer")
                || used.contains("offerLast");
        boolean frontRemove = used.contains("poll") || used.contains("pollFirst") || used.contains("remove()")
                || used.contains("removeFirst");
        boolean backRemove = used.contains("pollLast") || used.contains("removeLast");
        boolean indexed = used.contains("get") || used.contains("set") || used.contains("indexOf");

        if (stackOps && !backInsert) {
            return new Role("stack", "first"); // Deque.push/pop work at the front
        }
        if ((frontInsert && backInsert) || (frontRemove && backRemove) || (stackOps && backInsert)) {
            return new Role("deque", null);
        }
        if (type.equals("LinkedList") && (indexed || !(frontRemove || used.contains("peek") || used.contains("element")))) {
            return new Role("list", null);
        }
        return new Role("queue", null); // ArrayDeque by default, LinkedList used through Queue methods
    }

    /** What one call means for a structure in this role. */
    static String kind(String role, StructureOperation op) {
        String m = signature(op);
        switch (role) {
            case "stack":
                return switch (m) {
                    case "push", "addFirst", "offerFirst", "add", "addElement" -> "push";
                    case "pop", "removeFirst", "pollFirst", "poll", "remove()" -> "pop";
                    case "peek", "peekFirst", "getFirst", "element", "search", "get" -> "lookup";
                    default -> "other";
                };
            case "queue", "priority-queue":
                return switch (m) {
                    case "offer", "add", "addLast", "offerLast" -> "enqueue";
                    case "poll", "remove()", "removeFirst", "pollFirst" -> "dequeue";
                    case "peek", "element", "peekFirst", "getFirst", "contains", "get" -> "lookup";
                    case "remove" -> "remove";
                    default -> "other";
                };
            default:
                break;
        }
        return switch (m) {
            case "add", "addFirst", "addLast", "offer", "offerFirst", "offerLast", "push", "set", "put",
                 "putIfAbsent", "merge", "compute", "computeIfAbsent", "computeIfPresent", "replace" -> "insert";
            case "remove", "remove()", "removeFirst", "removeLast", "poll", "pollFirst", "pollLast", "pop",
                 "clear", "pollFirstEntry", "pollLastEntry" -> "remove";
            case "get", "getOrDefault", "containsKey", "containsValue", "contains", "indexOf", "peek",
                 "peekFirst", "peekLast", "element", "getFirst", "getLast", "firstKey", "lastKey", "first",
                 "last", "floorKey", "ceilingKey" -> "lookup";
            default -> "other";
        };
    }
}
