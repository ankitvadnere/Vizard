package com.vizard.api.dto.trace;

/**
 * Operation counts from the start of the program up to and including this step,
 * so stepping backwards rewinds them.
 *
 * @param steps          recorded steps so far
 * @param comparisons    evaluated conditions that compared at least one array element or a node's
 *                       value field (e.g. {@code arr[j] > arr[j + 1]}, {@code key < root.data})
 * @param swaps          two array cells exchanging values
 * @param arrayReads     array elements read by executed lines
 * @param arrayWrites    array elements assigned by executed lines
 * @param methodCalls    calls to the user's methods and constructors (main itself not counted)
 * @param maxDepth       deepest call stack so far (1 = only main)
 * @param loopIterations loop iterations started
 * @param pushes         stack pushes (Stack, or a Deque used through push/pop)
 * @param pops           stack pops
 * @param enqueues       elements added to a queue or priority queue
 * @param dequeues       elements removed from the front of a queue or priority queue
 * @param inserts        add / put / set on lists, maps, sets and deques
 * @param removes        removals from lists, maps, sets and deques
 * @param lookups        get / contains / containsKey / peek on any collection
 */
public record ExecutionStats(
        int steps,
        int comparisons,
        int swaps,
        int arrayReads,
        int arrayWrites,
        int methodCalls,
        int maxDepth,
        int loopIterations,
        int pushes,
        int pops,
        int enqueues,
        int dequeues,
        int inserts,
        int removes,
        int lookups
) {
}
