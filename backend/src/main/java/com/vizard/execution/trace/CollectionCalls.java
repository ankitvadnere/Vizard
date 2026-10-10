package com.vizard.execution.trace;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which calls count as collection operations, and where the user's code makes them.
 *
 * <p>Watching every method entry of HashMap or ArrayList would also stop on the thousands of
 * calls the JDK makes internally (Scanner, string concatenation, regex), slowing a trace 10×.
 * Instead Vizard puts a breakpoint on each call instruction of the lines that call one of
 * {@link #COUNTED_METHODS}, and from there watches only the single method that is entered next.
 */
public final class CollectionCalls {

    /** Collections whose contents Vizard can read and whose calls it counts. */
    public static final Set<String> COLLECTION_CLASSES = Set.of(
            "java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque", "java.util.Vector",
            "java.util.Stack", "java.util.PriorityQueue", "java.util.HashMap", "java.util.LinkedHashMap",
            "java.util.TreeMap", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet");

    /** Calls that change or read a collection's contents; size(), isEmpty(), iterator() etc. are not counted. */
    public static final Set<String> COUNTED_METHODS = Set.of(
            "push", "pop", "peek", "peekFirst", "peekLast", "offer", "offerFirst", "offerLast",
            "add", "addFirst", "addLast", "poll", "pollFirst", "pollLast", "remove", "removeFirst",
            "removeLast", "element", "getFirst", "getLast", "get", "set", "indexOf", "contains",
            "put", "getOrDefault", "containsKey", "containsValue", "putIfAbsent", "merge", "compute",
            "computeIfAbsent", "computeIfPresent", "replace", "clear", "search", "firstKey", "lastKey",
            "first", "last", "floorKey", "ceilingKey", "pollFirstEntry", "pollLastEntry");

    private static final int INVOKEVIRTUAL = 0xb6;
    private static final int INVOKEINTERFACE = 0xb9;

    private CollectionCalls() {
    }

    /** Code indexes of the instance-method calls (invokevirtual / invokeinterface) on the given lines. */
    static List<Long> callSites(Method method, Set<Integer> lines) throws AbsentInformationException {
        List<Long> sites = new ArrayList<>();
        if (lines.isEmpty()) {
            return sites;
        }
        byte[] code = method.bytecodes();
        List<Location> locations = method.allLineLocations();
        if (code == null || code.length == 0 || locations.isEmpty()) {
            return sites;
        }
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xff;
            if ((op == INVOKEVIRTUAL || op == INVOKEINTERFACE) && lines.contains(SameLineLoops.lineAt(locations, pc))) {
                sites.add((long) pc);
            }
            int length = SameLineLoops.instructionLength(code, pc, op);
            if (length <= 0) {
                break;
            }
            pc += length;
        }
        return sites;
    }
}
