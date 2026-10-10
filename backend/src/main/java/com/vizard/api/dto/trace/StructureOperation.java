package com.vizard.api.dto.trace;

import java.util.List;

/**
 * A call the user's code made directly on a standard collection, e.g. {@code st.push('(')}.
 * Recorded by the debugger as the call starts; attached to the first step recorded after it,
 * the step where its effect is visible.
 *
 * @param ref    heap id of the collection
 * @param type   its display type, e.g. "ArrayDeque"
 * @param method the method called, e.g. "push"
 * @param args   the arguments passed
 * @param line   the user's line that made the call
 * @param kind   what it means for this structure's role: "push", "pop", "enqueue", "dequeue",
 *               "insert", "remove", "lookup" or "other" (set after the run, once roles are known)
 */
public record StructureOperation(long ref, String type, String method, List<ValueSnapshot> args, int line,
                                 String kind) {

    public StructureOperation withKind(String newKind) {
        return new StructureOperation(ref, type, method, args, line, newKind);
    }
}
