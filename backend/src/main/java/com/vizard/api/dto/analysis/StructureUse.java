package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * A standard collection the program used, how it used it, and the cost of each operation it made.
 *
 * @param variable the variable that first referred to it, e.g. "stack"
 * @param type     the class, e.g. "ArrayDeque"
 * @param role     how it was used: "stack", "queue", "deque", "list", "priority-queue", "map", "set"
 * @param note     one sentence about the class's costs, e.g. why HashMap is O(1) only on average
 */
public record StructureUse(String variable, String type, String role, List<OperationCost> operations, String note) {
}
