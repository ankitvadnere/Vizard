package com.vizard.api.dto.analysis;

/**
 * The worst-case number of one kind of operation for the input size, to compare with the
 * live counter of the same name, e.g. comparisons ≤ n(n−1)/2 = 6.
 *
 * @param metric      name of the ExecutionStats counter it bounds: "comparisons" or "loopIterations"
 * @param description e.g. "element comparisons in the worst case"
 * @param formula     e.g. "n(n−1)/2"
 * @param value       the formula evaluated for n, or null when n is unknown
 * @param scope       "program" (whole run) or "per call" (e.g. each search)
 */
public record OperationBound(String metric, String description, String formula, Long value, String scope) {
}
