package com.vizard.api.dto.trace;

import java.util.List;

/**
 * An array or object referenced from a variable.
 *
 * @param kind      "array" or "object"
 * @param length    array length (0 for objects)
 * @param elements  array elements (possibly only the first N, see truncated)
 * @param fields    fields of objects whose class is defined in the user's program;
 *                  empty for library objects such as Scanner
 * @param truncated true if the array was longer than what was captured
 */
public record HeapObjectSnapshot(
        long id,
        String kind,
        String type,
        int length,
        List<ValueSnapshot> elements,
        List<VariableSnapshot> fields,
        boolean truncated
) {
}
