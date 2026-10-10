package com.vizard.execution.trace;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.vizard.api.dto.trace.HeapObjectSnapshot;
import com.vizard.api.dto.trace.ValueSnapshot;
import com.vizard.api.dto.trace.VariableSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Converts JDI values into snapshots for one step, collecting every array/object
 * reachable from the variables (up to a limit) into a heap map.
 *
 * <p>Objects are identified by their JVM id, so two variables pointing to the same array
 * share one heap entry; that's what lets later milestones draw aliasing (e.g. merge sort).
 */
final class HeapReader {

    static final int MAX_HEAP_OBJECTS = 150;
    static final int MAX_ARRAY_ELEMENTS = 100;
    private static final Set<String> PREV_LINKS = Set.of("prev", "previous");
    static final int MAX_STRING_LENGTH = 300;

    private static final Set<String> BOXED_TYPES = Set.of(
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Character", "java.lang.Boolean", "java.lang.Double", "java.lang.Float");

    private final Predicate<String> isUserClass;
    private final Deque<ObjectReference> pending = new ArrayDeque<>();
    private final Set<Long> seen = new HashSet<>();
    private final Map<String, HeapObjectSnapshot> heap = new LinkedHashMap<>();
    private final CollectionReader collections = new CollectionReader(this::value, MAX_ARRAY_ELEMENTS);

    HeapReader(Predicate<String> isUserClass) {
        this.isUserClass = isUserClass;
    }

    /** Converts one value; arrays/objects are queued for {@link #readHeap()}. */
    ValueSnapshot value(Value v) {
        if (v == null) {
            return ValueSnapshot.nullValue();
        }
        if (v instanceof PrimitiveValue p) {
            return primitive(p, p.type().name());
        }
        if (v instanceof StringReference s) {
            String text = s.value();
            return ValueSnapshot.string(text.length() > MAX_STRING_LENGTH
                    ? text.substring(0, MAX_STRING_LENGTH) + "…" : text);
        }
        if (v instanceof ObjectReference o) {
            String type = o.referenceType().name();
            if (BOXED_TYPES.contains(type)) {
                Field inner = o.referenceType().fieldByName("value");
                if (inner != null && o.getValue(inner) instanceof PrimitiveValue p) {
                    return primitive(p, simpleName(type));
                }
            }
            if (seen.add(o.uniqueID())) {
                pending.add(o);
            }
            return ValueSnapshot.reference(o.uniqueID(), displayType(type));
        }
        return ValueSnapshot.nullValue(); // void
    }

    /** Reads queued arrays/objects breadth-first until the limit. */
    Map<String, HeapObjectSnapshot> readHeap() {
        while (!pending.isEmpty() && heap.size() < MAX_HEAP_OBJECTS) {
            ObjectReference o = pending.poll();
            heap.put(Long.toString(o.uniqueID()), read(o));
        }
        return heap;
    }

    private HeapObjectSnapshot read(ObjectReference o) {
        String type = displayType(o.referenceType().name());

        if (o instanceof ArrayReference array) {
            int length = array.length();
            int captured = Math.min(length, MAX_ARRAY_ELEMENTS);
            List<ValueSnapshot> elements = new ArrayList<>(captured);
            if (captured > 0) {
                for (Value element : array.getValues(0, captured)) {
                    elements.add(value(element));
                }
            }
            return HeapObjectSnapshot.array(o.uniqueID(), type, length, elements, captured < length);
        }

        ReferenceType refType = o.referenceType();
        if (!isUserClass.test(refType.name())) {
            HeapObjectSnapshot collection = collections.read(o);
            if (collection != null) {
                return collection;
            }
            return HeapObjectSnapshot.object(o.uniqueID(), type, List.of(), null, null); // e.g. Scanner
        }

        List<VariableSnapshot> fields = new ArrayList<>();
        List<String> selfLinks = new ArrayList<>();
        if (refType instanceof ClassType classType) {
            List<Field> instanceFields = classType.allFields().stream()
                    .filter(f -> !f.isStatic() && !f.isSynthetic())
                    .toList();
            Map<Field, Value> values = o.getValues(instanceFields);
            for (Field f : instanceFields) {
                fields.add(new VariableSnapshot(f.name(), displayType(f.typeName()), value(values.get(f)), false));
                if (f.typeName().equals(refType.name())) {
                    selfLinks.add(f.name());
                }
            }
        }
        NodeShape shape = nodeShape(selfLinks);
        return HeapObjectSnapshot.object(o.uniqueID(), type, fields,
                shape == null ? null : shape.role(), shape == null ? null : shape.links());
    }

    record NodeShape(String role, List<String> links) {
    }

    /**
     * A user class whose fields point to its own class is a node: left + right → tree node;
     * one link (or next + prev) → linked-list node. Decided from the class's structure alone.
     */
    static NodeShape nodeShape(List<String> selfLinks) {
        if (selfLinks.isEmpty()) {
            return null;
        }
        String left = find(selfLinks, "left");
        String right = find(selfLinks, "right");
        if (left != null && right != null) {
            return new NodeShape("tree-node", List.of(left, right));
        }
        List<String> forward = selfLinks.stream()
                .filter(n -> !PREV_LINKS.contains(n.toLowerCase()) && !n.equalsIgnoreCase("parent")).toList();
        List<String> backward = selfLinks.stream().filter(n -> PREV_LINKS.contains(n.toLowerCase())).toList();
        if (forward.size() == 1 && backward.size() <= 1) {
            List<String> links = new ArrayList<>(forward);
            links.addAll(backward);
            return new NodeShape("list-node", List.copyOf(links));
        }
        if (forward.size() == 2 && backward.isEmpty()) {
            return new NodeShape("tree-node", List.copyOf(forward)); // two children, any names
        }
        return null;
    }

    private static String find(List<String> names, String wanted) {
        return names.stream().filter(n -> n.equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    private static ValueSnapshot primitive(PrimitiveValue p, String type) {
        if (p instanceof CharValue c) {
            return ValueSnapshot.primitive(type, String.valueOf(c.value()), "'" + c.value() + "'");
        }
        if (p instanceof BooleanValue b) {
            return ValueSnapshot.primitive(type, b.value(), String.valueOf(b.value()));
        }
        if (p instanceof DoubleValue d) {
            return decimal(type, d.value(), Double.toString(d.value()));
        }
        if (p instanceof FloatValue f) {
            return decimal(type, f.value(), Float.toString(f.value()));
        }
        long whole = p.longValue(); // byte, short, int, long
        return ValueSnapshot.primitive(type, whole, Long.toString(whole));
    }

    private static ValueSnapshot decimal(String type, double value, String display) {
        return ValueSnapshot.primitive(type, Double.isFinite(value) ? value : null, display);
    }

    /** "Main$Node" → "Node", "java.util.Scanner" → "Scanner", "int[]" stays. */
    static String displayType(String jvmName) {
        String simple = simpleName(jvmName);
        int dollar = simple.lastIndexOf('$');
        return dollar >= 0 ? simple.substring(dollar + 1) : simple;
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
