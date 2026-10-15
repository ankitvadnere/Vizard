# Testing

```powershell
cd backend
mvn test          # 163 tests, about 3 to 5 minutes (many tests really run and trace Java programs)
```

The tests are end-to-end where it matters: most of them send a real Java program through the real
pipeline (parse, policy, compile, sandboxed JVM, debugger) and check what comes back. Expected numbers
were worked out by hand or by an independent Python version of the same program, never copied from
Vizard's own output.

## What each test class checks

| Area | Class | Tests | Checks |
|---|---|---|---|
| Pipeline | `ExecutionServiceTest` | 13 | output, stdin, syntax/type errors with lines, runtime errors mapped to your line, timeout, memory limit, output cap, files/network/`System.exit` rejected |
| Security | `SecurityTest` | 9 | processes, threads (incl. parallel streams), reflection, environment, Unicode-escape tricks, forbidden imports, deep recursion, endless traces, endless growth runs |
| Tracing | `TraceServiceTest` | 9 | steps, call/return events, stack frames, heap objects, output offsets, step limit |
| Insight | `InsightTest` | 7 | condition values and results, accessed and swapped cells, loop iterations, pointers |
| Counting | `StatisticsTest` | 8 | comparisons and swaps against the theory (bubble, selection n(n−1)/2, quick sort vs an independent run) |
| Collections | `CollectionsTest`, `StructureStatisticsTest` | 3 + 7 | stacks, queues, maps, priority queues read correctly; their operations counted and classified |
| Graphs | `GraphModelBuilderTest`, `GraphAlgorithmsTest`, `GraphTraceTest` | 7 + 6 + 7 | BFS/DFS/Dijkstra recognised in many shapes, visit order, distances, relaxations |
| Backtracking | `BacktrackModelBuilderTest`, `BacktrackAlgorithmsTest`, `SearchTraceTest` | 6 + 4 + 5 | N-Queens (9 calls, 18 rejections to the first solution), permutations, subset sum |
| Dynamic programming | `DpModelBuilderTest`, `DpAlgorithmsTest`, `DpTraceTest` | 5 + 4 + 6 | tables vs look-alikes (prefix sums, Dijkstra's dist), fill order, memo hits, Fibonacci 25 / 11 calls |
| Branch and bound | `BnbModelBuilderTest`, `BnbAlgorithmsTest`, `BnbTraceTest` | 3 + 4 + 3 | knapsack 9 nodes / 4 expanded / best 65, TSP 65 vs 37 calls, assignment cost 13 |
| Recognition | `AlgorithmDetectorTest`, `StructureAlgorithmsTest` | 8 + 8 | sorts and searches by structure, BST, traversals, list reversal; look-alikes not misnamed |
| Estimates | `ComplexityEstimatorTest` | 10 | nested and sequential loops, log n / √n loops, calls, library costs, all three master-theorem cases, unrolled recurrences, honest "unknown" |
| Growth | `GrowthFitterTest`, `GrowthInstrumenterTest`, `GrowthTest` | 4 + 3 + 5 | curve fitting on synthetic data, what is scaled and counted, real runs (bubble sort n², merge sort n log n, Fibonacci 1.6ⁿ) |
| Code model | `CodeModelBuilderTest`, `NodeShapeTest` | 6 + 1 | conditions, loops, accesses; node shapes |
| Examples | `ExamplesTest` | 2 | **every program in the Example menu** compiles, traces within the step limit and is recognised as exactly the algorithms listed in `index.json` |

## Adding a test for a new example

Add the `.java` file to `backend/src/main/resources/examples/`, a line to `index.json` with the
algorithm ids it should be recognised as, and `ExamplesTest` covers it automatically.

## Checking the frontend

The frontend has no unit tests; it is a pure function of the JSON, and each milestone was checked in
a real browser (Chromium, driven by Playwright) on every example: no console errors, the right counters
and tree/table states at chosen steps, and screenshots compared by eye. To check by hand: open each
example, Step through, press End, and look at the Complexity tab.
