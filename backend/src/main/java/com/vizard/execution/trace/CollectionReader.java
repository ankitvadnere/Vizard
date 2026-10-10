package com.vizard.execution.trace;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;
import com.vizard.api.dto.trace.EntrySnapshot;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.ValueSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the contents of the standard collections through their <b>internal fields</b>, the same
 * way a debugger's variables view does. Nothing is invoked in the user's program (calling
 * {@code toArray()} would run code in it and could change what it does).
 *
 * <p>The field names are those of JDK 21 ({@code ArrayList.elementData}, {@code HashMap.table}, ...).
 * If a JDK ever renames one, the collection is shown by its type name only; nothing fails.
 */
final class CollectionReader {

    private final Function<Value, ValueSnapshot> convert;
    private final int maxElements;

    CollectionReader(Function<Value, ValueSnapshot> convert, int maxElements) {
        this.convert = convert;
        this.maxElements = maxElements;
    }

    /** A snapshot of a supported collection, or null if this object isn't one. */
    HeapObjectSnapshot read(ObjectReference o) {
        String jvmName = o.referenceType().name();
        try {
            return switch (jvmName) {
                case "java.util.ArrayList" -> list(o, "ArrayList", "list", "elementData", "size");
                case "java.util.Vector" -> list(o, "Vector", "list", "elementData", "elementCount");
                case "java.util.Stack" -> withTop(list(o, "Stack", "stack", "elementData", "elementCount"), "last");
                case "java.util.Arrays$ArrayList" -> wrappedArray(o, "a");
                case "java.util.ImmutableCollections$ListN" -> wrappedArray(o, "elements");
                case "java.util.ImmutableCollections$List12" -> list12(o);
                case "java.util.ArrayDeque" -> arrayDeque(o);
                case "java.util.LinkedList" -> linkedList(o);
                case "java.util.PriorityQueue" -> list(o, "PriorityQueue", "priority-queue", "queue", "size");
                case "java.util.HashMap" -> map(o, "HashMap", hashEntries(o));
                case "java.util.LinkedHashMap" -> map(o, "LinkedHashMap", linkedEntries(o));
                case "java.util.TreeMap" -> map(o, "TreeMap", treeEntries(o));
                case "java.util.HashSet" -> set(o, "HashSet", "map");
                case "java.util.LinkedHashSet" -> set(o, "LinkedHashSet", "map");
                case "java.util.TreeSet" -> set(o, "TreeSet", "m");
                default -> null;
            };
        } catch (MissingField e) {
            return null; // unknown JDK layout: shown as a plain object
        }
    }

    // ---------- sequences ---------------------------------------------------------------

    /** Backing array + size field: ArrayList, Vector, Stack, PriorityQueue (heap order). */
    private HeapObjectSnapshot list(ObjectReference o, String type, String role, String arrayField, String sizeField) {
        int size = intField(o, sizeField);
        Value backing = o.getValue(field(o, arrayField));
        List<ValueSnapshot> elements = backing instanceof ArrayReference a ? slice(a, 0, size) : List.of();
        return HeapObjectSnapshot.collection(o.uniqueID(), type, role, size, elements, null, size > elements.size(), null);
    }

    /** Arrays.asList(...) and List.of(a, b, c, ...): a final array field holds the elements. */
    private HeapObjectSnapshot wrappedArray(ObjectReference o, String arrayField) {
        Value backing = o.getValue(field(o, arrayField));
        if (!(backing instanceof ArrayReference a)) {
            return HeapObjectSnapshot.collection(o.uniqueID(), "List", "list", 0, List.of(), null, false, null);
        }
        List<ValueSnapshot> elements = slice(a, 0, a.length());
        return HeapObjectSnapshot.collection(o.uniqueID(), "List", "list", a.length(), elements, null,
                a.length() > elements.size(), null);
    }

    /** List.of(x) / List.of(x, y): fields e0 and e1; e1 holds ImmutableCollections.EMPTY when there is one element. */
    private HeapObjectSnapshot list12(ObjectReference o) {
        List<ValueSnapshot> elements = new ArrayList<>();
        elements.add(convert.apply(o.getValue(field(o, "e0"))));
        Value e1 = o.getValue(field(o, "e1"));
        Value empty = o.virtualMachine().classesByName("java.util.ImmutableCollections").stream()
                .map(outer -> {
                    Field f = outer.fieldByName("EMPTY");
                    return f == null ? null : outer.getValue(f);
                })
                .filter(v -> v != null)
                .findFirst().orElse(null);
        if (e1 != null && !e1.equals(empty)) {
            elements.add(convert.apply(e1));
        }
        return HeapObjectSnapshot.collection(o.uniqueID(), "List", "list", elements.size(), elements, null, false, null);
    }

    /** A circular array between head (first element) and tail (one past the last). */
    private HeapObjectSnapshot arrayDeque(ObjectReference o) {
        Value backing = o.getValue(field(o, "elements"));
        int head = intField(o, "head");
        int tail = intField(o, "tail");
        if (!(backing instanceof ArrayReference a) || a.length() == 0) {
            return HeapObjectSnapshot.collection(o.uniqueID(), "ArrayDeque", "deque", 0, List.of(), null, false, null);
        }
        int capacity = a.length();
        int size = Math.floorMod(tail - head, capacity);
        int wanted = Math.min(size, maxElements);
        List<ValueSnapshot> elements = new ArrayList<>(wanted);
        int firstRun = Math.min(wanted, capacity - head);
        if (firstRun > 0) {
            a.getValues(head, firstRun).forEach(v -> elements.add(convert.apply(v)));
        }
        if (wanted > firstRun) {
            a.getValues(0, wanted - firstRun).forEach(v -> elements.add(convert.apply(v)));
        }
        return HeapObjectSnapshot.collection(o.uniqueID(), "ArrayDeque", "deque", size, elements, null, size > wanted, null);
    }

    /** Doubly linked nodes from first; each node's item. Shown as a list until its use says otherwise. */
    private HeapObjectSnapshot linkedList(ObjectReference o) {
        int size = intField(o, "size");
        List<ValueSnapshot> elements = new ArrayList<>();
        Value node = o.getValue(field(o, "first"));
        while (node instanceof ObjectReference n && elements.size() < Math.min(size, maxElements)) {
            Field item = field(n, "item");
            Field next = field(n, "next");
            Map<Field, Value> values = n.getValues(List.of(item, next));
            elements.add(convert.apply(values.get(item)));
            node = values.get(next);
        }
        return HeapObjectSnapshot.collection(o.uniqueID(), "LinkedList", "list", size, elements, null,
                size > elements.size(), null);
    }

    // ---------- maps and sets -----------------------------------------------------------

    private record RawEntry(Value key, Value value) {
    }

    private HeapObjectSnapshot map(ObjectReference o, String type, List<RawEntry> raw) {
        int size = intField(o, "size");
        List<EntrySnapshot> entries = new ArrayList<>(raw.size());
        for (RawEntry e : raw) {
            entries.add(new EntrySnapshot(convert.apply(e.key()), convert.apply(e.value())));
        }
        return HeapObjectSnapshot.collection(o.uniqueID(), type, "map", size, List.of(), entries,
                size > entries.size(), null);
    }

    /** A set is a map whose keys are the members (HashSet.map, TreeSet.m). */
    private HeapObjectSnapshot set(ObjectReference o, String type, String mapField) {
        Value inner = o.getValue(field(o, mapField));
        if (!(inner instanceof ObjectReference m)) {
            return HeapObjectSnapshot.collection(o.uniqueID(), type, "set", 0, List.of(), null, false, null);
        }
        List<RawEntry> raw = switch (m.referenceType().name()) {
            case "java.util.HashMap" -> hashEntries(m);
            case "java.util.LinkedHashMap" -> linkedEntries(m);
            case "java.util.TreeMap" -> treeEntries(m);
            default -> throw new MissingField(mapField);
        };
        int size = intField(m, "size");
        List<ValueSnapshot> members = new ArrayList<>(raw.size());
        for (RawEntry e : raw) {
            members.add(convert.apply(e.key()));
        }
        return HeapObjectSnapshot.collection(o.uniqueID(), type, "set", size, members, null, size > members.size(), null);
    }

    /** HashMap: buckets of table in order, each bucket a chain of nodes (same order as iteration). */
    private List<RawEntry> hashEntries(ObjectReference map) {
        List<RawEntry> result = new ArrayList<>();
        Value table = map.getValue(field(map, "table"));
        if (!(table instanceof ArrayReference buckets)) {
            return result;
        }
        for (Value bucket : buckets.getValues()) {
            Value node = bucket;
            while (node instanceof ObjectReference n && result.size() < maxElements) {
                Field key = field(n, "key");
                Field value = field(n, "value");
                Field next = field(n, "next");
                Map<Field, Value> values = n.getValues(List.of(key, value, next));
                result.add(new RawEntry(values.get(key), values.get(value)));
                node = values.get(next);
            }
            if (result.size() >= maxElements) {
                break;
            }
        }
        return result;
    }

    /** LinkedHashMap: insertion order through head and each entry's after link. */
    private List<RawEntry> linkedEntries(ObjectReference map) {
        List<RawEntry> result = new ArrayList<>();
        Value node = map.getValue(field(map, "head"));
        while (node instanceof ObjectReference n && result.size() < maxElements) {
            Field key = field(n, "key");
            Field value = field(n, "value");
            Field after = field(n, "after");
            Map<Field, Value> values = n.getValues(List.of(key, value, after));
            result.add(new RawEntry(values.get(key), values.get(value)));
            node = values.get(after);
        }
        return result;
    }

    /** TreeMap: an in-order walk of the red-black tree, so keys come out sorted. */
    private List<RawEntry> treeEntries(ObjectReference map) {
        List<RawEntry> result = new ArrayList<>();
        Deque<ObjectReference> stack = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        Value current = map.getValue(field(map, "root"));
        while ((current instanceof ObjectReference || !stack.isEmpty()) && result.size() < maxElements) {
            while (current instanceof ObjectReference n && seen.add(n.uniqueID())) {
                stack.push(n);
                current = n.getValue(field(n, "left"));
            }
            if (stack.isEmpty()) {
                break;
            }
            ObjectReference n = stack.pop();
            result.add(new RawEntry(n.getValue(field(n, "key")), n.getValue(field(n, "value"))));
            current = n.getValue(field(n, "right"));
        }
        return result;
    }

    // ---------- helpers -----------------------------------------------------------------

    private List<ValueSnapshot> slice(ArrayReference a, int from, int count) {
        int n = Math.max(0, Math.min(Math.min(count, a.length() - from), maxElements));
        List<ValueSnapshot> out = new ArrayList<>(n);
        if (n > 0) {
            a.getValues(from, n).forEach(v -> out.add(convert.apply(v)));
        }
        return out;
    }

    private static HeapObjectSnapshot withTop(HeapObjectSnapshot s, String top) {
        return s.withRole(s.role(), top);
    }

    private static int intField(ObjectReference o, String name) {
        return o.getValue(field(o, name)) instanceof IntegerValue i ? i.value() : 0;
    }

    /** A visible field (own or inherited), or a MissingField signal if this JDK lays the class out differently. */
    private static Field field(ObjectReference o, String name) {
        Field f = o.referenceType().fieldByName(name);
        if (f == null) {
            throw new MissingField(name);
        }
        return f;
    }

    private static final class MissingField extends RuntimeException {
        MissingField(String name) {
            super(name, null, false, false);
        }
    }
}
