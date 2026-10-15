# Execution engine

How a program is checked, compiled, run, limited and traced.

## Steps

1. **Parse** with JavaParser (Java 17 syntax, Unicode escapes decoded first, as javac does).
   A syntax error is reported with javac's own message.
2. **Safety policy** (`SafetyPolicy`): an AST check that rejects what a DAA program never needs.
3. **Entry point**: the class with `public static void main(String[] args)`.
4. **Compile** in-process with `javax.tools` (`-proc:none`: annotation processors never run).
5. **Run** in a separate JVM (`LocalProcessSandbox`).
6. **Classify** the outcome (`RunOutcomeClassifier`): success, runtime error (with the line in your
   file, Vizard's launcher frames removed), timeout, out of memory, too much output.

## Supported Java

One source file with a `public static void main(String[] args)`; any number of classes and methods.
Imports allowed from `java.util`, `java.util.function`, `java.util.stream`, `java.math`.
Not allowed: file I/O, network, processes, threads, reflection, `System.exit`, native methods.

## Execution limits

Configured in `application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `vizard.execution.timeout-ms` | 5000 | Wall-clock limit per run |
| `vizard.execution.max-heap-mb` | 128 | `-Xmx` of the program's JVM |
| `vizard.execution.max-output-bytes` | 65536 | stdout cap; the program is stopped when exceeded |
| `vizard.execution.max-concurrent-runs` | 2 | Simultaneous runs and traces |
| `vizard.execution.trace-timeout-ms` | 15000 | Wall-clock limit for a traced run |
| `vizard.execution.max-trace-steps` | 3000 | Steps recorded; the rest of the program then runs untraced |

The growth check runs the program up to about fifteen times, each run under the same limits, and
stops early at 20,000,000 counted operations per run or 25 seconds in total.

## Security model and known limitations

User code never runs inside the Spring Boot process. It runs in a separate JVM with a timeout,
heap cap, output cap, one visible CPU, an empty environment and a throwaway working directory.
The AST-based `SafetyPolicy` rejects dangerous APIs before compiling.

This is a **development sandbox for local use**, not a hardened one: on Windows it cannot block
file or network access at the OS level, and native (off-heap) memory is not capped. The
`ExecutionSandbox` interface exists so a container-based sandbox (Docker with `--network none`,
read-only filesystem, cgroup CPU/memory limits) can replace `LocalProcessSandbox` without other changes.
Do not expose this server to the internet.

### What the policy rejects (tested in `SecurityTest`)

| Attempt | Result |
|---|---|
| `Runtime.getRuntime().exec(...)`, `ProcessBuilder`, `ProcessHandle` | rejected: Process control |
| `new Thread(...)`, `Thread.sleep`, `parallelStream()` | rejected: Threads |
| `Class.forName`, `getDeclaredMethods`, `newInstance` | rejected: Reflection |
| `System.getenv`, `System.getProperty`, `System.setOut`, `System.exit` | rejected: System access |
| `java.io.File`, `java.net.Socket`, imports outside `java.util*`/`java.math` | rejected |
| `\u0052untime.getRuntime()` (a name hidden in Unicode escapes) | rejected (escapes are decoded before checking) |
| infinite loop | stopped by the timeout |
| `new int[500_000_000]` | stopped by the heap limit |
| endless printing | stopped by the output limit |
| endless recursion | `StackOverflowError`, reported as a runtime error with its line |

## Tracing

### Why a debugger instead of rewriting the code

Tracing uses the Java Debug Interface, the same API IntelliJ's debugger uses, rather than
inserting `trace()` calls into the source. The user's code runs unmodified, so the recorded values
are exactly what the JVM computed; there is no re-implementation of Java's scoping rules that
could disagree with the compiler. The cost is speed (about 1,500 steps per second), which is why
traces are capped. The AST from JavaParser provides the meaning on top of the state.

The traced JVM starts suspended and connects to Vizard's debugger (JDI). A breakpoint on every line of
your classes, method-exit events, and breakpoints on the call instructions of lines that use a
collection record each step: the call stack with every local, static fields, every array and object
reachable from them (up to 150 objects per step), and how much had been printed. After
`max-trace-steps` steps the debugger lets go and the program finishes at full speed.

### How operations are counted

Every step carries running totals up to that step, so stepping back rewinds them:

| Counter | Counts |
|---|---|
| Comparisons | evaluated conditions that compared at least one array element (`arr[j] > arr[j + 1]`, `arr[mid] == target`); short-circuited parts are not counted |
| Swaps | two cells exchanging values, written from different lines (`a[i] = a[j]; a[j] = t;`) or a `swap()` call. Copying a temp array back in merge sort is *not* a swap |
| Array reads / writes | element accesses by lines that ran |
| Loop iterations | iterations started, in any loop |
| Method calls | calls to your methods and constructors (main not counted) |
| Max depth | deepest call stack (1 = only main) |
| Pushes / pops | stack operations (`Stack`, or a `Deque` used through `push`/`pop`) |
| Enqueues / dequeues | queue and priority-queue operations (`offer`/`add` at the rear, `poll`/`remove()` at the front) |
| Inserts / removes / lookups | `add`, `put`, `set` / `remove` / `get`, `contains`, `containsKey`, `peek` on any collection |
| Vertices visited | graph vertices visited so far (settled, for Dijkstra) |
| Edges examined | iterations of the neighbour loop: one per edge looked at, one per cell for a matrix |
| Relaxations | distance improvements `dist[v] = dist[u] + w` |
| Nodes explored | calls of a backtracking method (nodes of the state-space tree) |
| Pruned | choices rejected by the check, plus calls cut by a bound check |
| Solutions | nodes where a solution was recorded |
| Backtracks | returns from a call to the level above, where the choice is undone |
| Cells filled | distinct cells of DP or memo tables given a value |
| Memo hits | calls answered from the memo instead of being computed again |
| Repeated calls | plain recursion: calls whose arguments were already computed (wasted work) |

Comparisons also count conditions that compare a node's value field, such as `key < root.key` in a BST;
pointer checks like `root.left == null` are not comparisons. Only the counters a program uses are shown.

The counts for the examples were checked against independent implementations (for instance quick
sort on {10, 80, 30, 90, 40, 50, 70}: 13 comparisons, 5 swaps).

### How a condition's result is decided

For `if (arr[j] > arr[j + 1])` Vizard shows `5 > 2` and `true`. The values come from a small
evaluator that reads the recorded state; it never runs code, and it treats anything with side
effects (method calls, `i++`, assignments) as unknown. The true/false result is taken from **what
actually happened next**: if the next line in the same method call is inside the branch, the
condition was true. That works even for `if (isPrime(n))`, which the evaluator can't compute.
For `if`/`while` the evaluator is used first (Vizard stops exactly where those conditions are
evaluated); for a `for` header it's the other way round, because that stop happens before the
loop's update runs.

## Tracing limitations

- Loops written on one line (`for (...) sum += i;`) are stepped through every iteration (an extra
  breakpoint is placed on the loop's jump-back target), but they get no iteration counter.
- When a `for` loop ends, its variable is already out of scope, so the final check shows as
  `j < 3` is `false` rather than `3 < 3`.
- Collections are read from JDK 21's internal fields. Other collection classes (and wrappers such as
  `Collections.unmodifiableList`) show their type name only. At most 100 elements are shown.
- Only calls your code makes directly on a collection are counted; a call made inside a library method
  (`Collections.sort(list)`) is not. `remove()` on a `PriorityQueue` isn't counted (it lives in a
  superclass).
- Trees show up to 63 nodes; lists 12 nodes per row.
- Graphs are found through a loop over a vertex's neighbours. A graph made of node objects that hold a
  list of neighbour objects (`node.neighbors`), an edge list (`int[][] edges` scanned as a whole, as in
  Bellman–Ford) and grid BFS (`grid[r][c]` with direction arrays) are not drawn as graphs; their arrays
  and collections are still shown. Vertices must be ints, chars or strings. Graphs are laid out for
  readability up to about 15 vertices.
- User methods called *by library code* (a comparator lambda inside `PriorityQueue`, `compareTo` during
  `Collections.sort`, `toString` during string concatenation) run without being stepped through.
- Backtracking needs a recursive method that goes one level deeper per call with a goal test at the
  top (or a combination loop). Searches written with an explicit stack, recursion over a 2-D grid
  (`solve(r, c + 1)` with wrap-around, as in many Sudoku solvers) and mutual recursion are not drawn as
  state-space trees. Trees show the first 1500 nodes. A solution is detected by its effect (output,
  a counter, a stored result, `return true` at a leaf), so a solution that is only kept in a local
  variable is drawn as a dead end.
- Rejected choices are shown when the check is an `if` around the recursive call or an
  `if (...) continue;` before it, and its result is known (a method call such as `isSafe(...)`, or
  values Vizard can evaluate).
- DP tables are found when the method creates the array it fills; a table created in `main` and filled
  in another method, or filled by recursion without a memo check, is shown as a plain array. Tables up to
  600 cells are drawn as a table. Only arrays get the table view; a `HashMap` memo still gets the
  recursion tree.
- Algorithm recognition covers the textbook shapes of thirty-nine algorithms. Unusual implementations
  (e.g. a three-way quick sort, or merge sort without a separate merge loop) may not be recognised,
  in which case no complexity is claimed. Counts stay exact either way.
- Comparisons are counted per evaluated condition, so `if (a[i] > x && a[i] < y)` counts once.
- Pointers are detected from how variables are used (`arr[j]`, `mid = low + (high - low) / 2`,
  `i < arr.length`); an index variable used in other ways may not get a caret.
- At most 3000 steps are recorded; arrays show their first 100 elements and each step keeps up to 150 objects.
- The first step may be a class's static setup (`static int count = 0;`), which Java really runs before `main`.
