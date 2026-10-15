# Visualization

The frontend draws step k as a pure function of the recorded data: the state at step k plus, for
trees and tables, the parts that existed by step k. That is what makes stepping backwards exact and
cheap: nothing is replayed.

## Layout of the screen

- **Editor** (Monaco): the line about to run is highlighted; problems are underlined.
- **Visualization**: the insight strip (the condition just evaluated with its values, loop iteration,
  swap), then arrays (cells, pointer carets, compared/read/written/swapped cells, the active range),
  collections, linked lists, trees, graphs, state-space trees, boards, DP tables, recursion trees and
  branch-and-bound trees, whichever the program has. **Full screen** (button or `F`) gives the drawing
  the whole window; the playback controls move with it.
- **Call stack**: one card per frame, the current one highlighted.
- **Operation counts**: collapsed by default under the drawing; counters that changed on this step are
  highlighted.
- **Variables / Complexity** tabs; **Output / Problems / Input** tabs.
- **Keyboard**: `?` lists every shortcut.

## How data structures are read and drawn

**Collections.** `CollectionReader` reads `ArrayList`, `LinkedList`, `ArrayDeque`, `Stack`/`Vector`,
`PriorityQueue`, `HashMap`, `LinkedHashMap`, `TreeMap`, `HashSet`, `LinkedHashSet`, `TreeSet`,
`List.of(...)` and `Arrays.asList(...)` through their internal fields, the way a debugger's variables
view does. Nothing is called inside the program (calling `toArray()` would run code in it). The
elements come out in Java's own iteration order: an `ArrayDeque` from head round its circular buffer,
a `TreeMap` by an in-order walk, a `PriorityQueue` in heap-array order.

**Operations.** Watching every method entry of `HashMap` would also catch the thousands of calls the
JDK makes internally (Scanner, string concatenation), slowing a trace ten-fold. Instead, on each line
that calls `push`, `poll`, `put`... (known from the AST), Vizard puts a breakpoint on every call
instruction, and from there catches only the method entered next. It is counted if a user line called
it directly on a collection, so `HashSet.add` calling `HashMap.put` inside is one operation, not two.
Each operation is attached to the first step recorded after it: the step where its effect is visible.

**Roles.** How a collection is drawn depends on how it was *used*, decided from all its operations:
an `ArrayDeque` that only sees `push`/`pop` is a stack (top first); fed `offer`/`poll` it is a queue;
a `LinkedList` used through `get(i)` is a list. `java.util.Stack` keeps its top last.

**Your own nodes.** A class with fields of its own type is a node: `left` + `right` → tree node;
one link (plus an optional `prev`) → linked-list node. Lists are drawn as chains, one row per chain
(a reversal in progress is two chains); trees with x = inorder position and y = depth, so a BST reads
sorted. Variables label the nodes they point at; the node the current call is at is filled and the
nodes its callers are at are outlined, which shows the path a recursion took.

| Structure | Drawn as | Highlighted each step |
|---|---|---|
| Stack | upright column, top at the top | pushed element; popped element as a ghost |
| Queue / deque | row, front → rear | enqueued element; dequeued element as a ghost |
| Priority queue | heap tree + its array | positions whose value changed |
| Map / set | key → value table / chips | new or changed entries; looked-up keys outlined |
| List / array | indexed boxes (lists of lists as rows) | as arrays |
| Linked list | `[value│•]` boxes and arrows | new nodes; links that changed (amber) |
| Binary tree | nodes and edges | current node, call path, new nodes and edges |

## How graphs are found and drawn

**Finding the graph.** Every graph algorithm has a *loop over the neighbours of a vertex*.
`GraphModelBuilder` finds it in the AST in any of its usual forms, whatever the names:

| Form | Example |
|---|---|
| Adjacency list | `for (int v : adj.get(u))`, `for (int v : adj[u])`, `for (String v : graph.get(u))` (a map) |
| Weighted list | `for (int[] e : adj.get(u))` with `e[0]`/`e[1]`, or `for (Edge e : adj.get(u))` with `e.to` |
| Index or iterator | `for (int i = 0; i < adj.get(u).size(); i++)`, `Iterator<Integer> it = adj[u].listIterator()` |
| Adjacency matrix | `for (int v = 0; v < n; v++) if (g[u][v] != 0 ...)` |

A loop only counts if the neighbour is then *used as a vertex*: it indexes a 1-D array (`visited[v]`),
goes into a collection (`queue.offer(v)`) or into a recursive call (`dfs(v)`). That is what separates
a graph from a DP table (`dp[i][w]`), Floyd–Warshall or a matrix product, which only index 2-D arrays,
and from N-Queens (`isSafe(board, row, col)` is a helper call, not a vertex use).

Around the loop, the same AST shows the traversal's state: the **visited** array or set (checked for
the neighbour, marked somewhere with `= true` or `add`), the **frontier** (the collection the
neighbour is added to and the vertex is taken from: FIFO queue, stack or priority queue), the
**distance** array (`dist[v] = dist[u] + w`, a *relaxation* when guarded by `dist[u] + w < dist[v]`,
a *BFS level* when it adds 1) and a **parent** array (`parent[v] = u`).

**Rebuilding it at every step.** `GraphTracker` reads the graph object from the heap at each step
(so a graph being built in `main` grows edge by edge), and the state from the variables named above:
the current vertex `u`, the edge being examined (while inside the loop body), visited vertices,
the frontier's contents, distances (`∞` for `Integer.MAX_VALUE`), the recursion path (one vertex per
`dfs` call on the stack), the visit order, and the tree edges: from the parent array if there is one,
otherwise the vertex that set a distance, discovered the vertex, or pushed it.
Undirected = every edge u → v has a matching v → u; each such pair is drawn once.

**Drawing it.** The layout is computed once per trace from the most complete graph, by a
deterministic force-directed algorithm (Fruchterman–Reingold started from a circle), so vertices
never move while you step and the same graph always looks the same.

| Vertex / edge | Meaning |
|---|---|
| Amber fill | the vertex being processed (`u`) |
| Dashed amber outline | waiting in the queue / stack / priority queue |
| Amber outline | on the recursion path (recursive DFS) |
| Green | visited (or processed, for Dijkstra) |
| `#n` | position in the visit order |
| `d=…` | distance, amber when it just changed |
| Thick amber edge | the edge being examined |
| Green edge | tree edge (BFS tree, DFS tree, shortest-path tree) |

**Recognition and complexity** (`GraphAlgorithms`):

| Algorithm | Recognised by | Time | Edges examined ≤ |
|---|---|---|---|
| BFS | FIFO queue + visited marks (or `dist[v] = dist[u] + 1`) | O(V + E) list, O(V²) matrix | 2E undirected / E directed; V² matrix |
| DFS | recursion on unvisited neighbours, or a stack + visited | O(V + E) list, O(V²) matrix | same |
| Dijkstra | relaxation + priority queue | O((V+E) log V) | 2E (if outdated entries are skipped) |
| Dijkstra | relaxation + scan for the closest vertex | O(V²) | V² (matrix) |

The representation the code actually uses decides which line applies. Prim's MST (`key[v] = w`
without adding), Kahn's topological sort (in-degrees, no visited marks) and Bellman–Ford/SPFA
(relaxation from a plain queue) share the neighbour loop and are drawn, but deliberately not
reported as BFS or Dijkstra. A helper such as `minDistance()` inside a recognised Dijkstra is part of
it, not reported on its own.

**Library callbacks.** A comparator lambda `(a, b) -> a[0] - b[0]` is called by `PriorityQueue`
on every offer and poll. Those calls come from inside the JDK, not from the program's own control
flow, so the tracer lets them run without stopping (likewise `compareTo` during `Collections.sort`).

## How backtracking is recognised and drawn

**The shape.** `BacktrackModelBuilder` looks for a recursive method that goes **one level deeper** per
call and has a **goal test** at that depth:

```java
static boolean solve(int[][] board, int row) {
    if (row == N) return true;                       // goal test
    for (int col = 0; col < N; col++) {              // one branch per choice
        if (isSafe(board, row, col)) {               // check: a false here prunes the branch
            board[row][col] = 1;                     // choose
            if (solve(board, row + 1)) return true;  // explore one level deeper
            board[row][col] = 0;                     // undo = backtrack
        }
    }
    return false;
}
```

or, without a loop, two calls that take and leave one element (`sub(i + 1, sum + a[i])`,
`sub(i + 1, sum)`). "One level deeper" means every recursive call passes `row + 1` (or `i + 1` of the
choice loop, for combinations); the depth can also be implicit, as in `path.size() == nums.length`.
That is what separates a search from merge sort (`lo..mid`, `mid+1..hi`), Fibonacci (`n − 1`, `n − 2`),
Towers of Hanoi, tree traversals and graph DFS (whose call sits in a loop over neighbours).

**The problem**, which decides the size of the tree, is read from what the choice does:

| Problem | Recognised by | Time | Full tree (no pruning) |
|---|---|---|---|
| N-Queens | a queen on `board[row][col]`, or `queens[row] = col` checked along diagonals (`Math.abs`, `row + c` / `row − c` arrays) | O(n!) | 1 + n + … + nⁿ |
| Permutations | any element not used yet (`!used[i]`, `contains`), or swap / recurse / swap back | O(n·n!) | Σ n!/(n−k)! |
| Subsets / subset sum | take / leave each element, or extend a combination with a later one | O(2ⁿ) | 2ⁿ⁺¹ − 1 |
| Graph colouring | `color[v] = c`, checked against row `v` of the adjacency matrix | O(mⁿ) | 1 + m + … + mⁿ |
| Anything else with the shape | | O(bᵈ) | 1 + b + … + bᵈ |

n (the depth) comes from the goal test and m or b from the choice loop's range, evaluated at the first
call of this run. The Complexity tab shows the nodes explored against the full tree.

**The tree** (`SearchTracker`) is built from the real calls: a node per call, its parent the call it
came from, labelled with the choice (the loop variable, the element it picks such as `nums[i]`, or
`+x` / `−x` for take / leave).

| Node | Meaning | How Vizard knows |
|---|---|---|
| Amber | the call running now, and the path to it | still on the call stack |
| Red square | a choice the check rejected; never called | the check around the recursive call was false (shown once the check has returned) |
| Red dashed | a call cut short | a bound check such as `if (sum > target) return;` was true |
| Green ★ | a solution | while it was the deepest call the program printed, counted (`count++`), stored a result, or the leaf returned `true`. Changes to the search's own state (board, path, used) don't count |
| Dashed grey | a dead end | a leaf that is none of the above |

The whole tree is sent once (`analysis.searchTrees`); every node carries the step it appears at, the
last step its call ran and the step its outcome is known, so the browser draws the tree as it was at
any step, and clicking a node jumps to its call. For N-Queens the board is drawn from the program's
own array; for graph colouring the graph's vertices are painted with `color[v]`.

**Full screen and a quieter panel.** The Full screen button (or F) gives the Visualization panel
the whole screen for big graphs, trees and boards; the step controls move into it and show the line
being run as text. Esc leaves. The operation counters sit in a collapsed "Operation counts" section
under the visualization (it remembers whether you opened it).

**Step over / step out.** Backtracking traces are long, so two playback controls skip whole calls:
*step over* (Shift+→) runs the calls made on the current line without stopping, and *step out*
(Shift+↑) finishes the current call and stops back in its caller.

## How dynamic programming is recognised and drawn

**Tabulation (bottom-up).** `DpModelBuilder` looks for an array the method *creates* and fills inside
loops from cells of the same array: `dp[i] = dp[i - 1] + dp[i - 2]`, or a local computed from it
(`int cost = dp[i][k] + dp[k + 1][j] + …`). Reading the same array is the recurrence. An insertion
sort (whose array is a parameter), a prefix sum (`p[i] = p[i - 1] + a[i]`, one look back in one loop)
and the `dist[]` of BFS or Dijkstra (graph state, drawn on the graph) are not DP tables.

**Memoisation (top-down).** A recursive method that checks a memo first and stores into it:
`if (memo[n] != -1) return memo[n];` … `memo[n] = …` (a `HashMap` with `containsKey` / `put` works too).

**Plain recursion** with two or more recursive calls whose arguments are the parameters plus or minus
constants (`fib(n - 1) + fib(n - 2)`) is drawn as a recursion tree, with every call whose arguments
were already computed marked as a **repeat**: the work DP saves.

| Problem | Named by | Time | Space |
|---|---|---|---|
| Fibonacci, plain recursion | `f(n − 1) + f(n − 2)`, nothing remembered | O(2ⁿ) (2·F(n+1) − 1 calls) | O(n) |
| Fibonacci, memoised / tabulated | the same with a memo / a table | O(n) | O(n) |
| 0/1 knapsack | `max` with the capacity reduced by an item's weight | O(n·W) | O(n·W) |
| Longest common subsequence | letters compared (`charAt`), `max` | O(m·n) | O(m·n) |
| Edit distance | letters compared, `min` of three neighbours | O(m·n) | O(m·n) |
| Coin change | a 1-D table looking back by a coin's value, `dp[a − coin]` | O(A·k) | O(A) |
| Matrix chain | `dp[i][k] + dp[k + 1][j]` over a split point k | O(n³) | O(n²) |
| Any other table / memo | the shape alone | from the loops / the number of states | the table |

Recursive knapsack *without* a memo stays what Milestone 7 calls it (a take/leave search, O(2ⁿ)); add a
memo and it becomes dynamic programming, O(n·W).

**The table view** (`DpTracker`, `DpTableVisualizer`): on a line that uses the table, the cell being
computed is amber, the cells it reads are blue with arrows into it, and below the table the line is
shown as written and with its values (`dp[3][5] = Math.max(dp[i][w], dp[i - 1][w - wt[i - 1]] + val[i - 1])`
→ `max(35, 15 + 30)` → `45`). Filled cells are green, cells not computed yet are faded, and cells the
program only ever reads (the zero row of a knapsack table) are marked as base values. Every write is
recorded with the step at which its value appears (`analysis.dpTables`), so stepping backwards un-fills
the table. LCS and edit-distance tables are labelled with the letters of the two strings.

**The recursion tree** (`RecursionTracker`) reuses the Milestone 7 tree: one node per call, labelled
with its arguments. A memo hit (the call returned from the memo check) is drawn blue and dashed: its
subtree was never built.

## How branch and bound is recognised and drawn

**With a live-node list** (`BnbModelBuilder`): a `while` loop that takes a node of *your* class out of a
queue, stack or priority queue (`Node u = pq.poll();`), creates new nodes of that class from it, and
compares a field of a node with a "best so far" variable (`if (u.bound <= best) continue;`,
`if (v.bound > best) pq.add(v);`). The list decides the strategy: a priority queue is best-first
(least-cost) branch and bound, a queue FIFO, a stack LIFO. A `bound()` helper that divides (takes a
fraction of the next item) names the **0/1 knapsack** with the fractional-knapsack bound. Dijkstra's
priority queue holds `int[]`, not node objects, so it is not mistaken for branch and bound.

**Depth-first, recursive**: a backtracking method (Milestone 7) that keeps a best-so-far variable,
updates it at the goal and compares against it in its check is backtracking *with a bound*. The cost
matrix it reads names the problem: `dist[city][next]` (the row is the current city) is the
**travelling salesman**, `cost[worker][job]` (the row is the level) is **job assignment**. The
same TSP without the bound is reported as plain backtracking, so the example shows both side by side.

| Problem | Bound | Worst case | Why the worst case stays |
|---|---|---|---|
| 0/1 knapsack (best-first) | fractional knapsack of the remaining items | O(2ⁿ) nodes | nothing may be prunable |
| Travelling salesman | cost so far (+ next edge) vs best tour | O(n!) | every order may need completing |
| Job assignment | cost so far + cheapest free job of each remaining worker | O(n!) | as above |

The complexity is always the worst case of the method, never a claim about your run; the run's own
counts (generated, expanded, pruned) are shown next to it, with the full tree size for this n.

**The tree view** (`BnbTracker`, `SearchTreeVisualizer`): every new node object is a tree node whose
parent is the node being expanded at that moment; it is followed through the list: generated (faded),
live (dashed amber, waiting in the list), taken out (amber), expanded (grey) or pruned (red dashed:
its bound could not beat the best). Below the tree: the best so far, the live list in the order it
will be served, and the node just taken out. Every change of the best variable is recorded with the
node that caused it (★). Counters: nodes generated, expanded, pruned by bound, best improved.
