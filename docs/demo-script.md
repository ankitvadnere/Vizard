# Demo script (about 12 minutes)

A walk through Vizard for a viva or presentation. Each part says what to click, what to point at,
and the DAA idea it shows. Start the backend first (`mvn spring-boot:run` in `backend`) and open
http://localhost:18080. Press `?` at any time for the shortcuts.

## 1. The problem (30 s)

"Textbooks show complexity as a formula. Vizard shows it happening: it runs real Java, records every
step with a debugger, recognises the algorithm from the code's structure, and puts the textbook
complexity next to the exact count for this run."

## 2. Sorting: counting matches the theory (2 min)

1. Example → **Bubble sort** → **Step through**.
2. Press `→` a few times: the condition strip shows `arr[j] > arr[j + 1]` with values and the result;
   compared cells are outlined, a swap is highlighted.
3. Open **Operation counts** under the drawing: comparisons and swaps go up as you step.
4. Press `End`, open **Complexity**: *Bubble sort, O(n²) worst*, with "n(n−1)/2 = … comparisons" and
   this run's count. Open "Why Vizard recognised it": nested loops, neighbour comparison, swap: by
   structure, not by the method's name.

**DAA idea:** worst-case analysis, counting the basic operation.

## 3. Divide and conquer (1 min)

**Merge sort** → Step through → `End` → Complexity: O(n log n). Point out the active range dimming
during the recursive calls. Then **Measure growth**: the dots lie on a straight line of slope ≈ 1.1 on
log-log axes, closest curve n log n.

**DAA idea:** recurrences, T(n) = 2T(n/2) + O(n).

## 4. Graphs (1.5 min)

**Dijkstra (priority queue)** → Step through → `F` for full screen. Step: the current vertex, the
edge being relaxed, the distance table changing, the priority queue's contents. Press `Esc`.

**DAA idea:** greedy choice; O((V + E) log V) with a heap vs O(V²) with the matrix version.

## 5. Backtracking (1.5 min)

**N-Queens (4 × 4)** → Step through → step until the tree grows. Red squares are columns the safety check
rejected, dashed nodes are dead ends, ★ the solution. Click a node to jump to that call.

**DAA idea:** state-space tree, pruning by feasibility, O(n!) worst case vs 9 calls actually made.

## 6. Dynamic programming (2 min)

1. **Fibonacci three ways** → Step through → `End` → `F`: the plain recursion tree has 25 calls,
   18 of them repeats (red); the memoised one 11, 3 answered from the memo (blue dashed).
2. **0/1 knapsack (table)**: step to a `dp[i][w]` line: the cell being computed, arrows from the
   cells it reads, and the formula with values: `max(35, 15 + 30) → 45`.

**DAA idea:** overlapping subproblems and optimal substructure; O(2ⁿ) → O(n·W).

## 7. Branch and bound (1.5 min)

1. **0/1 knapsack (best-first branch and bound)**: each node shows its bound; the live list under
   the tree is ordered by bound; red dashed nodes are pruned because their bound cannot beat the best.
2. **Travelling salesman**: two trees side by side: 65 calls for plain backtracking vs 37 with the bound.

**DAA idea:** bounding functions, best-first search; same O(n!) worst case, far fewer nodes in practice.

## 8. Your own code (2 min)

1. Example → **Your own code: estimated complexity** (or type a method of your own).
2. Step through → Complexity: neither method is a known algorithm, so the cards are tagged
   *Estimate*: `maxSubarray` is O(n²) (two nested loops over `a.length`), `power` is
   T(n) = T(n/2) + O(1) → master theorem case 2 → O(log n). Open "How it was estimated".
3. **Measure growth**: closest curve n², "agrees with O(n²) found from the code".
4. Optional: write a three-way recursion `t(n/2) + t(n/2) + t(n/2)` and show master theorem case 1,
   O(n^1.58).

**DAA idea:** analysing arbitrary code: loop counting, recurrences, the master theorem, and checking
the analysis empirically with a doubling experiment.

## 9. Safety (30 s)

Type `Runtime.getRuntime().exec("calc");` in main and Run: rejected before compiling ("Process
control"). An infinite loop is stopped by the 5-second limit.

## Questions that often come up

- **"How do you know it's bubble sort?"** The AST is matched against the algorithm's defining
  structure; evidence is listed on the card. Renaming the method changes nothing.
- **"Can it find the complexity of any code?"** It *estimates* it from loops, calls and recurrences,
  labels it as an estimate, and the growth check measures it. It never presents an estimate as a fact.
- **"Why a debugger?"** The code runs unmodified, so every value is what the JVM computed.
- **"What DAA did you use to build it?"** Tree layout (tidy drawing), graph layout (force-directed),
  shortest-path tree reconstruction, prefix sums for statistics, binary search over step indices,
  stack discipline for call/return, least-squares curve fitting, and the master theorem in the estimator.
