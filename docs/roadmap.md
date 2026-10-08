# Vizard roadmap

Updated after Milestone 4. The original brief planned seven milestones; this version adds
backtracking, dynamic programming and branch and bound, the core design paradigms of a DAA
course, each with its own visualization and complexity analysis.

## Done

| Milestone | What it delivered |
|---|---|
| 1. Execution | Browser editor → Spring Boot → compile → sandboxed run → output and errors |
| 2. Tracing | Step forward/back through a real JVM trace (debugger-based): line, variables, call stack, output |
| 3. Visualization | Arrays as SVG with index pointers, compared/changed cells, animated swaps, condition cards, loop counters |
| 4. Sorting & searching | Live operation counts, active ranges, algorithm recognition + complexity catalogue for 7 algorithms |

## Principles for every remaining milestone

These come from what Milestones 1–4 taught us, including the N-Queens misdetection.

1. **Visualize from execution data only.** Every picture is drawn from the recorded trace; nothing is
   simulated in the browser.
2. **Recognise by structure, report with evidence.** A complexity is shown only for an algorithm
   Vizard identified, together with the reasons. Unknown code gets exact counts and no Big-O claim.
3. **Never report a part as the whole.** A helper inside an unrecognised algorithm is not reported on
   its own (the N-Queens `isSafe()` lesson).
4. **Every recogniser ships with look-alike tests**: programs that resemble the pattern but are not it.
5. **Theory next to the run.** Wherever a bound exists, show the worst case for this input beside the
   live count (as Milestone 4 does for comparisons).
6. **Keep inputs teachable.** Examples use small inputs (n = 4–8) so a trace fits in the step limit
   and a student can follow every step.

## Milestone 5 — Data structures and collections

Prerequisite for everything after it: graphs, backtracking, DP and branch and bound all keep their
state in collections.

- **Collection contents in the trace**: `ArrayList`, `LinkedList`, `ArrayDeque`, `Stack`,
  `PriorityQueue`, `HashMap`, `HashSet`, `TreeMap`, read through their public structure so the
  trace shows elements, not just the type name.
- **Visualizers**
  - Stack: vertical, top marked, push/pop animated
  - Queue / deque: front → rear, enqueue/dequeue animated
  - Linked list (user `Node` classes and `java.util.LinkedList`): boxes and arrows, `head`/`curr` pointers
  - Binary tree / BST (user `Node` classes with `left`/`right`): tree layout, path of the current search
  - HashMap / HashSet: key → value table; buckets optional
  - PriorityQueue: shown as its heap tree and as an array
- **Statistics**: pushes, pops, enqueues, dequeues, node visits, map lookups.
- **Recognition & complexity**: stack and queue usage, BST insert/search/delete, tree traversals
  (pre/in/post/level order), with per-operation costs (e.g. BST search O(h): O(log n) balanced,
  O(n) skewed, with the current tree height shown).
- **Examples**: stack (balanced brackets), queue, linked list reverse, BST insert/search, traversals.

## Milestone 6 — Graphs

- **Graph detection** from adjacency matrices (`int[][]`), adjacency lists
  (`List<List<Integer>>`, `List<Integer>[]`, `Map<Integer, List<…>>`) and edge lists.
- **Graph visualizer**: nodes and edges (D3 layout), weights on edges, current node, visited set,
  frontier highlighted; the queue (BFS), stack/recursion (DFS) or priority queue (Dijkstra) shown
  beside it.
- **Algorithms**: BFS, DFS (recursive and iterative), Dijkstra, with traversal order, distance
  table and the final shortest-path tree.
- **Complexity**: BFS/DFS O(V + E) for lists, O(V²) for a matrix; Dijkstra O((V + E) log V) with a
  binary-heap priority queue, O(V²) with a plain array. The representation actually used decides which.
- **Statistics**: vertices visited, edges examined, relaxations.

## Milestone 7 — Backtracking

- **Recognition**: the *choose → check → recurse → undo* shape: a recursive method with a loop over
  choices, a feasibility check, a recursive call, and the choice undone after it.
- **State-space tree visualizer** (new, reused by Milestones 8 and 9): every recursive call is a
  node; the current path is highlighted, pruned branches are marked, solutions are marked. Built
  from the call/return events already in the trace.
- **Problem views**: N-Queens board (from the 2D-array drawing), subset sum, permutations, graph colouring.
- **Statistics**: nodes explored, branches pruned, solutions found, backtracks.
- **Complexity**: N-Queens O(n!), subsets O(2ⁿ), permutations O(n·n!), with nodes explored shown
  against the size of the full tree, which makes the effect of pruning visible.
- **Step over / step out**: new playback controls to skip a whole recursive call, needed once
  traces are hundreds of steps long.

## Milestone 8 — Dynamic programming

- **Recognition**
  - Memoisation: a recursive method that checks a memo array/map before computing and stores the
    result after.
  - Tabulation: nested loops filling `dp[i]` / `dp[i][j]` from earlier cells.
- **Visualizers**
  - DP table filling cell by cell; the cells read to compute the current one are highlighted
    (the recurrence made visible).
  - For memoisation: the recursion tree with memo hits shown as cut-off nodes.
- **Examples**: Fibonacci three ways (naive, memoised, tabulated, compared side by side),
  0/1 knapsack, LCS, coin change, edit distance, matrix chain multiplication.
- **Statistics**: cells computed, memo hits vs misses, subproblems solved.
- **Complexity**: from the table's dimensions × work per cell, e.g. knapsack O(n·W), LCS O(m·n);
  naive Fibonacci O(2ⁿ) vs O(n), with the call counts of both shown.

## Milestone 9 — Branch and bound

Builds on the state-space tree (Milestone 7) and `PriorityQueue` visibility (Milestone 5).

- **Recognition**: search over a state space with a bound function and pruning against the best
  solution so far; best-first (priority queue), FIFO and LIFO variants.
- **Visualizer**: the state-space tree with each node's bound; live, expanded and pruned nodes
  distinguished; the current best solution; the priority queue's contents.
- **Examples**: 0/1 knapsack (fractional-knapsack bound), travelling salesman (4–5 cities),
  job assignment.
- **Statistics**: nodes generated, expanded, pruned by bound; improvements of the best solution.
- **Complexity**: worst case O(2ⁿ) for knapsack, O(n!) for TSP, shown next to the nodes actually
  explored, plus a side-by-side comparison with the backtracking version of the same problem.

## Milestone 10 — Complexity of unrecognised code, polish, documentation

- **Static estimate for unrecognised code**, clearly labelled as an estimate with its reasoning:
  loop nesting over the input size, halving loops (log n), simple recurrences
  (e.g. T(n) = 2T(n/2) + O(n) → O(n log n) via the master theorem).
- **Empirical check**: run the program at several input sizes and plot the operation counts
  against n, n log n, n² and 2ⁿ (if time allows).
- **Polish**: UI consistency, keyboard shortcuts help, error messages, loading states.
- **Hardening**: security tests from the brief, step and memory limits revisited.
- **Documentation**: final README and `docs/architecture.md`, `execution-engine.md`,
  `visualization.md`, `api.md`, `testing.md`, plus screenshots and a demo script.

## Future scope (unchanged from the brief)

Interactive graph creation, recording/export, shareable visualizations, algorithm comparison mode,
performance graphs, optional AI explanations, classroom mode.
