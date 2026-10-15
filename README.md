# Vizard

Interactive Java code execution, step-by-step debugging and algorithm visualization, built as a
Design and Analysis of Algorithms course project.

Write or pick a Java program, press **Step through**, and move forwards and backwards through its
execution while Vizard draws what it is doing: arrays with pointers and swaps, stacks, queues, heaps,
maps, linked lists and trees, graphs being traversed, backtracking state-space trees, dynamic
programming tables filling cell by cell, recursion trees with memo hits, and branch-and-bound trees
with their live nodes and bounds. Next to the picture: exact operation counts for the run, the
textbook complexity of 39 recognised algorithms, an estimated complexity for any other code (loops,
recurrences, the master theorem), and a growth check that runs the program at bigger and bigger inputs
and fits the curve.

> Status: **complete** (Milestones 1–10). See [docs/roadmap.md](docs/roadmap.md).

## Screenshots

Bubble sort, the step that completes a swap: both cells highlighted, the Swaps counter just increased.

![Bubble sort swap](docs/screenshots/bubble-sort-swap.png)

Binary search: cells outside `low..high` are dimmed, the step explains the jump back to the loop condition.

![Binary search range](docs/screenshots/binary-search-range.png)

Merge sort finished: the Complexity tab compares the worst case for n = 7 with this run's count.

![Merge sort complexity](docs/screenshots/merge-sort-complexity.png)

Linked list reversal half-way: the list is two chains, the link just turned around is amber, and
`curr`, `prev` and `next` label the nodes they point at.

![Linked list reversal](docs/screenshots/linked-list-reverse.png)

Recursive BST insert: the node this call is at is filled, the path the recursion took is outlined,
and the Complexity tab shows the tree's measured height.

![BST insert](docs/screenshots/bst-insert.png)

Bracket matching with a stack: the popped `(` stays one step as a ghost above the new top.

![Stack pop](docs/screenshots/stack-pop.png)

BFS: vertex 1 is being processed and edge 1 → 4 examined; 2 and 3 wait in the queue (dashed), the
green edges are the BFS tree so far and `#n` is the visit order.

![BFS](docs/screenshots/bfs.png)

Recursive DFS: the outlined vertices are the recursion path 0 → 1 → 3 → 5, one per call on the stack.

![DFS](docs/screenshots/dfs-recursive.png)

Dijkstra with a priority queue: `d=` under each vertex is its distance, the one just relaxed in
amber; green edges form the shortest-path tree. The Complexity tab compares 2E with the edges examined.

![Dijkstra](docs/screenshots/dijkstra-heap.png)

Dijkstra on an adjacency matrix: every cell of the processed row is checked, so the bound is V².

![Dijkstra on a matrix](docs/screenshots/dijkstra-matrix.png)

N-Queens as it finds its first solution: the state-space tree (amber = the path of running calls,
red squares = columns the safety check rejected, dashed = dead ends, green ★ = solution) next to the
board, where the square being tried is outlined and attacked squares are shaded.

![N-Queens](docs/screenshots/n-queens.png)

Subset sum: each level takes (`+3`) or leaves (`−3`) one element; red dashed calls were cut by
`if (sum > target) return;`. The Complexity tab compares the 27 calls made with the 31 of the full tree.

![Subset sum](docs/screenshots/subset-sum.png)

Permutations: every leaf is an ordering; the `used[]` check rejects elements already taken.

![Permutations](docs/screenshots/permutations.png)

0/1 knapsack, bottom-up: `dp[3][5]` is being computed from the cells it reads (blue, with arrows),
and the line is shown with its values: `max(35, 15 + 30)` → 45.

![Knapsack table](docs/screenshots/knapsack-table.png)

Fibonacci three ways, full screen: plain recursion makes 25 calls, 18 of them repeats (red); the
memoised version makes 11, and 3 are answered from the memo (blue, dashed) without recursing.

![Fibonacci recursion trees](docs/screenshots/fibonacci-trees.png)

Longest common subsequence: rows and columns are labelled with the letters of the two strings.

![LCS table](docs/screenshots/lcs-table.png)

0/1 knapsack by best-first branch and bound: each node shows its bound; the dashed amber nodes are
live in the priority queue, red dashed ones were pruned, ★ marks the nodes that improved the best.

![Knapsack branch and bound](docs/screenshots/knapsack-bnb.png)

Travelling salesman with the bound `cost + dist[city][next] < best`: 37 calls instead of the 65 of
plain backtracking on the same 5 cities.

![TSP branch and bound](docs/screenshots/tsp-bnb.png)

Your own code: a method Vizard does not recognise gets an *estimate* read from its loops, with the
reasoning shown step by step.

![Estimated complexity](docs/screenshots/estimate.png)

Measure growth: the same program run at n = 4 … 2048 with generated input; on log-log axes the counts
lie on a line of slope 2, closest to n², agreeing with the estimate.

![Growth check](docs/screenshots/growth.png)

## Features

| | |
|---|---|
| **Run** | compile and run Java 17+ in a sandboxed JVM; output, stdin, compile errors and runtime errors mapped to your lines |
| **Step through** | every line recorded with a debugger; step forwards/backwards, step over, step out, play; jump to any step |
| **Data** | variables, call stack, arrays (pointers, compared/swapped/written cells, active range), collections, linked lists, binary trees |
| **Graphs** | BFS, DFS, Dijkstra: visit order, frontier, distances, relaxed edges, shortest-path tree |
| **Backtracking** | state-space tree with rejected choices, dead ends and solutions; chessboard for N-Queens |
| **Dynamic programming** | tables filling with the cells each one reads; recursion trees with repeats or memo hits |
| **Branch and bound** | live, expanded and pruned nodes with bounds; the best solution so far; best-first, FIFO, LIFO |
| **Complexity** | 39 algorithms recognised by structure with best/average/worst/space and the worst-case count for this n; estimates for everything else; growth measurement |
| **Counts** | comparisons, swaps, reads, writes, iterations, calls, depth, collection operations, vertices/edges, nodes, cells, memo hits, bound prunes |
| **View** | full-screen visualization (`F`), collapsible counters, keyboard shortcuts (`?`) |

## Quick start

Requirements: **JDK 21** (a JDK, not a JRE: Vizard compiles your code), **Maven 3.9+** (bundled with
IntelliJ IDEA), and internet access in the browser (the Monaco editor loads from a CDN).

```powershell
cd backend
mvn spring-boot:run
```

Open **http://localhost:18080** (run it from the `backend` folder: the frontend is served from `../frontend`).

```powershell
cd backend
mvn test          # 163 tests
```

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | the pipeline, how static analysis and the debugger meet, project structure, design decisions |
| [docs/execution-engine.md](docs/execution-engine.md) | parsing, safety policy, compiling, the sandbox and its limits, tracing, how counting works, limitations |
| [docs/visualization.md](docs/visualization.md) | how each structure is found and drawn: arrays, collections, trees, graphs, backtracking, DP, branch and bound |
| [docs/complexity.md](docs/complexity.md) | recognised algorithms, the static estimator (loops, recurrences, master theorem), the growth check |
| [docs/api.md](docs/api.md) | the REST API: `/api/execute`, `/api/trace`, `/api/growth`, `/api/examples`, `/api/health` |
| [docs/testing.md](docs/testing.md) | what the 163 tests check and how expected values were obtained |
| [docs/demo-script.md](docs/demo-script.md) | a 12-minute walk-through for a viva or presentation |
| [docs/roadmap.md](docs/roadmap.md) | the ten milestones |

## Limits worth knowing

- One source file with a `main` method; imports from `java.util`, `java.util.function`,
  `java.util.stream`, `java.math`. No files, network, threads, reflection or `System.exit`.
- Runs: 5 s, 128 MB heap, 64 KB of output. Traces: 3,000 steps, 15 s.
- An estimate is an upper bound read from the code's shape, and a growth measurement describes the runs
  it made; both are labelled as such. Only recognised algorithms get a textbook complexity.
- Full list: [execution-engine.md § Tracing limitations](docs/execution-engine.md#tracing-limitations).

**Security:** user code runs in a separate, resource-limited JVM after an AST safety check, but this is
a development sandbox for local use. Do not expose the server to the internet.
