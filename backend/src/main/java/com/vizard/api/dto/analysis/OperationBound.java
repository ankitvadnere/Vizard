package com.vizard.api.dto.analysis;

/**
 * The worst-case number of one kind of operation for the input size, to compare with the
 * live counter of the same name, e.g. comparisons ≤ n(n−1)/2 = 6.
 *
 * @param metric      name of the ExecutionStats counter it bounds: "comparisons", "edgesExamined",
 *                    "nodesExplored"
 * @param description e.g. "element comparisons in the worst case"
 * @param formula     e.g. "n(n−1)/2"
 * @param value       the formula evaluated for this run, or null when the size is unknown
 * @param scope       "program" (whole run), "per search", "per traversal"
 * @param size        the size the value is for, when it is not the array length n: "V = 6, E = 7", "n = 4";
 *                    null to use the program's input size
 */
public record OperationBound(String metric, String description, String formula, Long value, String scope,
                             String size) {

    public OperationBound(String metric, String description, String formula, Long value, String scope) {
        this(metric, description, formula, value, scope, null);
    }
}
