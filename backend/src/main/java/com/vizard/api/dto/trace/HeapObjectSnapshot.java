package com.vizard.api.dto.trace;

import java.util.List;

/**
 * An array, a collection or an object referenced from a variable.
 *
 * @param kind      "array", "collection" or "object"
 * @param type      display type: "int[]", "ArrayDeque", "Node"
 * @param length    array length or collection size (0 for objects)
 * @param elements  array or collection elements in iteration order (possibly only the first N);
 *                  for a set, its members; for a priority queue, its heap array
 * @param fields    fields of objects whose class is defined in the user's program;
 *                  empty for library objects such as Scanner
 * @param truncated true if more elements exist than were captured
 * @param entries   key → value pairs of a map, else null
 * @param role      how the structure is drawn: for collections "list", "stack", "queue", "deque",
 *                  "priority-queue", "map" or "set" (refined from the operations the program used);
 *                  for user objects "list-node" or "tree-node" when the class links to itself; else null
 * @param links     for nodes, the fields that link to other nodes: ["next"], ["next", "prev"], ["left", "right"]
 * @param top       for stacks, which end is the top: "first" or "last"
 */
public record HeapObjectSnapshot(
        long id,
        String kind,
        String type,
        int length,
        List<ValueSnapshot> elements,
        List<VariableSnapshot> fields,
        boolean truncated,
        List<EntrySnapshot> entries,
        String role,
        List<String> links,
        String top
) {

    public static HeapObjectSnapshot array(long id, String type, int length, List<ValueSnapshot> elements,
                                           boolean truncated) {
        return new HeapObjectSnapshot(id, "array", type, length, elements, List.of(), truncated,
                null, null, null, null);
    }

    public static HeapObjectSnapshot object(long id, String type, List<VariableSnapshot> fields,
                                            String role, List<String> links) {
        return new HeapObjectSnapshot(id, "object", type, 0, List.of(), fields, false, null, role, links, null);
    }

    public static HeapObjectSnapshot collection(long id, String type, String role, int size,
                                                List<ValueSnapshot> elements, List<EntrySnapshot> entries,
                                                boolean truncated, String top) {
        return new HeapObjectSnapshot(id, "collection", type, size, elements, List.of(), truncated,
                entries, role, null, top);
    }

    public HeapObjectSnapshot withRole(String newRole, String newTop) {
        return new HeapObjectSnapshot(id, kind, type, length, elements, fields, truncated, entries, newRole,
                links, newTop);
    }
}
