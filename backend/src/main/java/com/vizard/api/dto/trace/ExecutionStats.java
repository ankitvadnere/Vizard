package com.vizard.api.dto.trace;

/**
 * Operation counts from the start of the program up to and including this step,
 * so stepping backwards rewinds them.
 *
 * @param steps          recorded steps so far
 * @param comparisons    evaluated conditions that compared at least one array element
 *                       (e.g. {@code arr[j] > arr[j + 1]}, {@code arr[mid] == target})
 * @param swaps          two array cells exchanging values
 * @param arrayReads     array elements read by executed lines
 * @param arrayWrites    array elements assigned by executed lines
 * @param methodCalls    calls to the user's methods and constructors (main itself not counted)
 * @param maxDepth       deepest call stack so far (1 = only main)
 * @param loopIterations loop iterations started
 */
public record ExecutionStats(
        int steps,
        int comparisons,
        int swaps,
        int arrayReads,
        int arrayWrites,
        int methodCalls,
        int maxDepth,
        int loopIterations
) {
}
