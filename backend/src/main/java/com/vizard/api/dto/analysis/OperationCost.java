package com.vizard.api.dto.analysis;

/**
 * One kind of call made on a collection: how many times this run made it, and what one call costs.
 *
 * @param cost e.g. "O(1)", "O(1) amortized", "O(log n)", "O(1) average"
 */
public record OperationCost(String method, int count, String cost) {
}
