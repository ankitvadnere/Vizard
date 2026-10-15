# Complexity analysis

Vizard says three different things about running time, and labels each so they are never confused:

| | Source | Shown as | Claims |
|---|---|---|---|
| **Recognised algorithm** | a catalogue of 39 textbook algorithms, matched by code structure | a card with best / average / worst / space | the textbook complexity, adjusted for details it can see |
| **Estimate** | reading the loops, calls and recursion of any other method | a dashed card tagged *Estimate*, with its reasoning | an upper bound for the worst case |
| **Growth check** | running the program at growing input sizes and counting | a log-log chart with the closest curve | how *these runs* grew; evidence, not proof |

And for every run, the **operation counts** (comparisons, swaps, iterations, nodes, cells...) are exact.

## 1. Recognised algorithms: the catalogue

`AlgorithmDetector` matches the defining *structure* of each algorithm in the AST; method names are
never used. For example, bubble sort = two nested loops + an `if` comparing neighbouring elements
`a[j]` and `a[j + 1]` + a swap inside that `if`. Recognisers exist for bubble, selection, insertion,
merge and quick sort and for linear and binary search. Each match records its evidence in plain words
("Compares neighbouring elements arr[j] and arr[j + 1] (line 7)"), shown in the Complexity tab.

The complexity itself is a **known-algorithm mapping** (`AlgorithmCatalog`), adjusted for details
Vizard can see: bubble sort's best case is O(n) only if it has an early-exit flag; recursive binary
search needs O(log n) space. Code that matches nothing is never given a catalogue
entry; it gets a clearly labelled *estimate* instead (below).

For the input of the current run (n = length of the largest array used), the catalogue also gives
the worst-case number of element comparisons, e.g. n(n−1)/2 = 6 for bubble sort on 4 elements,
shown next to the live count.

Recognised (category: algorithms):

- **Sorting**: bubble, selection, insertion, merge, quick sort
- **Searching**: linear, binary search
- **Trees and lists**: BST search, insertion, deletion; preorder, inorder, postorder, level-order traversal;
  linked-list reversal
- **Graphs**: BFS, DFS (recursive and with a stack), Dijkstra (heap or matrix)
- **Backtracking**: N-Queens, permutations, subsets / subset sum, graph colouring, travelling salesman,
  job assignment, any other backtracking search
- **Dynamic programming**: Fibonacci (plain, memoised, tabulated), 0/1 knapsack, LCS, edit distance,
  coin change, matrix chain, any other memo or table
- **Branch and bound**: 0/1 knapsack (best-first, fractional bound), travelling salesman, job assignment,
  any other live-node search

## 2. Estimates for everything else

`ComplexityEstimator` reads each method that is not a recognised algorithm and builds a big-O
expression (`Cost`: a sum of products of n, log n, √n, 2ⁿ, n!, per size symbol; dominated terms are
dropped, so O(n² + n) becomes O(n²) but O(n + m) stays).

**Loops.** How many times a loop can run is read from its header and the updates in its body:

| Loop | Iterations |
|---|---|
| `for (i = 0; i < n; i++)`, `i += 2`, `for (x : arr)` | n (the bound) |
| `for (i = n; i > 0; i--)` | n (the start) |
| `for (i = 1; i < n; i *= 2)`, `while (n > 0) n /= 10` | log n |
| `for (i = 2; i * i <= n; i++)` | √n |
| `while (lo <= hi) { mid = (lo + hi) / 2; lo = mid + 1 / hi = mid − 1 }` | log n |
| `for (x = head; x != null; x = x.next)` | n = nodes in the list |
| `for (i = 0; i < 100; i++)` | a constant |
| `for (j = i; j < n; j++)` inside a loop over i | n (an upper bound: the triangle i..n) |
| anything else (`while (n != 1) n = n % 2 == 0 ? n / 2 : 3n + 1`) | k, "the iterations of the loop on line 12": said, not guessed |

**Combining.** Nested loops multiply; statements one after another add; an `if` costs its larger
branch (the worst case). `new int[n][m]` costs n·m (Java fills it with zeros).

**Calls.** A call to one of your methods costs what that method costs, with its parameters replaced
by the arguments: `merge(a, lo, mid, hi)` costs the size of the range `hi − lo` *in the caller*. Index
variables inside a range (`lo`, `hi`, `mid`, `i = lo`) are treated as positions, so `hi − lo + 1` is
one size n, not lo + hi. Library calls with a known cost: `Arrays.sort` n log n, `Arrays.fill`,
`copyOf`, `System.arraycopy`, `String.substring` n, `List.contains/indexOf` n, `PriorityQueue` and
`TreeMap` operations log n. Everything else counts as O(1).

**Sizes get names.** Each size gets a letter with a legend: `n = arr.length`, `m = grid[i].length`,
`n = hi − lo + 1, the size of the range`, `n = nodes reachable from head`. If the code says
`int n = arr.length`, the letter is n.

**Recursion becomes a recurrence.** For a method that calls itself, Vizard counts the calls made per
invocation (an `if/else` counts its larger branch) and how each call shrinks a parameter:

| Shape | Recurrence | Solved by | Result |
|---|---|---|---|
| `f(n − 1)` once | T(n) = T(n − 1) + f(n) | unrolling: n levels | n · f(n) |
| `f(n − 1)` twice (`hanoi`), `f(n − 1) + f(n − 2)` (`fib`) | T(n) = 2T(n − 1) + O(1) | recursion tree: 2 calls per level, n levels | O(2ⁿ) (fib: φⁿ ≈ 1.618ⁿ, noted) |
| `f(i + 1)` with base case `i == a.length` | T(n) = T(n − 1) + f(n) | unrolling | n · f(n) |
| `f(k + 1)` inside a loop over k..n | T(n) = n · T(n − 1) + f(n) | unrolling | n! · f(n) |
| `f(n / 2)` | T(n) = aT(n/b) + f(n) | **master theorem** | below |
| `f(lo, mid)`, `f(mid + 1, hi)` | T(n) = 2T(n/2) + f(n) | master theorem | |

**Master theorem.** For T(n) = a·T(n/b) + Θ(nᵈ logᵏ n), compare log_b a with d:

- log_b a < d (case 3): the top call's work dominates → Θ(nᵈ logᵏ n)
- log_b a = d (case 2): every level does the same work, log n levels → Θ(nᵈ logᵏ⁺¹ n)
- log_b a > d (case 1): the leaves dominate → Θ(n^(log_b a)), e.g. 3T(n/2) + O(1) → O(n^1.58)

Examples: merge sort written by you (not recognised, say) → T(n) = 2T(n/2) + O(n) → case 2 → O(n log n);
fast power → T(n) = T(n/2) + O(1) → case 2 → O(log n).

**Space** is the recursion depth (n for subtract-and-conquer, log n for halving) plus the arrays the
method allocates, including those of methods it calls.

**When there is no estimate.** If a recursive call does not shrink a parameter in a way Vizard can
follow (`gcd(b, a % b)`), or the calls shrink in different ways, the card says so instead of guessing.

![Estimate card](screenshots/estimate.png)

### Why this is an estimate

It is an upper bound for the worst case read from the code's shape. It cannot see that a loop exits
early, that data makes a branch impossible, or that a `while` loop's bound depends on values; and
it treats library calls it does not know as O(1). That is why the card is tagged *Estimate*, shows every
step of its reasoning, and why the growth check exists.

## 3. Measuring growth

**Measure growth** in the Complexity tab runs the program again, many times, with bigger inputs.

1. **What gets bigger** (`GrowthInstrumenter`), in this order of preference:
   - the longest array written in the code (`int[] arr = {5, 3, 8, 1}`), and any other array of the
     same length (parallel arrays such as weights and values); the values are random (seeded, so every
     run is repeatable) in the original range, sorted if the original was sorted (binary search needs it);
   - else an `int n = 10` (or N, size, len...) in main or as a field;
   - else the strings in main, or string literals passed to your methods (`lcs("ABCBDAB", "BDCABA")`),
     made of the original letters.
   A program that reads the keyboard has nothing in the code to scale, and is told so.
2. **What is counted**: one operation per loop iteration and per call of your methods. Work done inside
   library methods (`Arrays.sort`, `HashMap.put`) is not counted.
3. **Sizes**: n = 4, 8, 16, ... (2, 4, 8, ... for a size variable) doubling until a run passes 2,000,000 operations (or 10 sizes). If the work
   explodes early (2ⁿ, n!), sizes in between are filled in. Each run is a fresh sandboxed JVM with
   printing switched off; a run is stopped at 20,000,000 operations.
4. **Fitting** (`GrowthFitter`): for each curve f in 1, log n, n, n log n, n², n³, n!, n·n!, find c in
   operations ≈ c·f(n) on a log scale (so every size counts equally) and the root-mean-square error;
   exponentials get their own base b (bⁿ), which is how naive Fibonacci shows up as 1.62ⁿ = φⁿ rather than
   2ⁿ. Sizes below 8 are left out when enough larger ones exist (they are mostly constant overhead).
5. **The chart**: log-log axes for polynomials (nᵏ is a straight line of slope k, and the slope is
   reported), n on a plain axis for exponentials (bⁿ is then straight); the closest curve solid, the next
   closest dashed, the measurements as dots. The result is compared with what the code says
   (recognised or estimated).

![Growth check](screenshots/growth.png)

| Program | Measured | From the code |
|---|---|---|
| bubble sort | n², slope 1.99 | O(n²) |
| merge sort | n log n | O(n log n) |
| quick sort (random data) | n log n | O(n²) worst, O(n log n) average |
| naive Fibonacci | 1.62ⁿ | O(2ⁿ) (an upper bound) |
| permutations | n · n! | O(n·n!) |
| LCS (two strings of length n) | n² | O(m·n) |
| 0/1 knapsack table (capacity fixed) | n | O(n·W), W does not grow |

The last rows show why a measurement is evidence rather than proof: quick sort on random data is
not its worst case, and the knapsack's capacity stays as written.
