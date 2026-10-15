# API

## `POST /api/execute`

```json
{ "code": "public class Main { ... }", "stdin": "optional input" }
```

Response (always HTTP 200 for anything the user's program did):

```json
{
  "status": "SUCCESS | COMPILATION_ERROR | UNSUPPORTED_FEATURE | RUNTIME_ERROR | TIMEOUT | MEMORY_LIMIT_EXCEEDED | OUTPUT_LIMIT_EXCEEDED | INVALID_REQUEST | SERVER_BUSY | INTERNAL_ERROR",
  "success": true,
  "message": "Program finished successfully.",
  "stdout": "...",
  "stderr": "",
  "exitCode": 0,
  "compileTimeMs": 310,
  "runTimeMs": 95,
  "problems": [ { "line": 3, "column": 18, "message": "';' expected", "severity": "ERROR" } ],
  "runtimeError": { "exceptionType": "java.lang.ArithmeticException", "message": "/ by zero", "line": 5 }
}
```

Fields that are null are left out of the JSON.

## `POST /api/trace`

Same request. Response:

```json
{
  "execution": { "...": "same shape as /api/execute" },
  "truncated": false,
  "maxSteps": 3000,
  "steps": [
    {
      "index": 6,
      "event": "LINE",
      "line": 9,
      "depth": 1,
      "stack": [
        { "className": "Main", "methodName": "main", "line": 9,
          "variables": [
            { "name": "arr", "type": "int[]", "argument": false,
              "value": { "kind": "ref", "type": "int[]", "display": "int[]", "ref": 57 } },
            { "name": "temp", "type": "int", "argument": false,
              "value": { "kind": "primitive", "type": "int", "value": 5, "display": "5" } }
          ] }
      ],
      "statics": [],
      "heap": {
        "57": { "id": 57, "kind": "array", "type": "int[]", "length": 4, "truncated": false,
                "elements": [ { "kind": "primitive", "type": "int", "value": 2, "display": "2" } ],
                "fields": [] }
      },
      "outputLength": 0
    }
  ]
}
```

Every step also has an `insight`:

```json
"insight": {
  "condition": { "kind": "if", "line": 6, "text": "arr[j] > arr[j + 1]", "explanation": "5 > 2", "result": true },
  "accesses":  [ { "array": "arr", "ref": 57, "index": 0, "text": "arr[j]", "write": false, "compared": true } ],
  "changes":   [ { "ref": 57, "indices": [1] } ],
  "swap":      { "ref": 57, "first": 0, "second": 1 },
  "pointers":  [ { "array": "arr", "ref": 57, "variable": "j", "index": 0 } ],
  "loops":     [ { "kind": "for", "line": 5, "variable": "j", "iteration": 1 } ]
}
```

plus `"ranges": [ { "array": "arr", "fromVariable": "low", "from": 4, "toVariable": "high", "to": 6 } ]`
and `"loopBackTo": 5` when stopped on a loop's closing brace. Each step also has running `stats`:

```json
"stats": { "steps": 8, "comparisons": 1, "swaps": 1, "arrayReads": 4, "arrayWrites": 2,
           "methodCalls": 0, "maxDepth": 1, "loopIterations": 2 }
```

(plus the collection counters, `verticesVisited`, `edgesExamined`, `relaxations`, for
backtracking `nodesExplored`, `branchesPruned`, `solutionsFound`, `backtracks`, and for dynamic
programming `cellsFilled`, `memoHits`, `repeatedCalls`). On a line that uses a DP table, `insight.dp`
says what it computes:

```json
"dp": { "table": "dp", "ref": 63, "cell": [3, 5], "reads": [[3, 5], [2, 1]], "label": "dp[i][w]",
        "formula": "Math.max(dp[i][w], dp[i - 1][w - wt[i - 1]] + val[i - 1])",
        "values": "max(35, 15 + 30)", "result": "45", "write": true }
```

and `analysis.dpTables` lists each table with its size, labels and fill order
(`{ "row": 3, "col": 5, "step": 74 }`, one entry per write). When the program
has a graph, `insight.graph` describes it at that step:

```json
"graph": {
  "name": "adj", "ref": 41, "representation": "adjacency-list", "directed": false, "weighted": true,
  "vertices": ["0", "1", "2", "3", "4"],
  "edges": [ { "from": "0", "to": "1", "weight": "4" }, { "from": "0", "to": "2", "weight": "1" } ],
  "current": "1", "examining": { "from": "1", "to": "3", "weight": "1" },
  "visited": [], "frontier": ["1", "3"], "frontierName": "pq", "frontierOrder": "priority",
  "onStack": [], "order": ["0", "2", "1"],
  "treeEdges": [ { "from": "2", "to": "1" }, { "from": "0", "to": "2" } ],
  "distances": [ { "vertex": "0", "value": "0" }, { "vertex": "4", "value": "∞" } ], "distName": "dist",
  "truncated": false
}
```

The response has an `analysis`:

```json
"analysis": {
  "input": { "name": "arr", "n": 4 },
  "algorithms": [ {
    "id": "bubble-sort", "name": "Bubble sort", "category": "Sorting", "method": "main", "line": 2,
    "evidence": [ "Two nested loops in main().", "Compares neighbouring elements arr[j] and arr[j + 1] (line 7)." ],
    "complexity": { "best": "O(n²)", "average": "O(n²)", "worst": "O(n²)", "space": "O(1)", "stable": true, "note": "..." },
    "bound": { "metric": "comparisons", "description": "element comparisons (exactly, for any input)",
               "formula": "n(n−1)/2", "value": 6, "scope": "program" }
  } ]
}
```

For a backtracking program the analysis also has `searchTrees`:

```json
"searchTrees": [ {
  "method": "solve", "problem": "n-queens", "kind": "loop", "depthVariable": "row", "choiceVariable": "col",
  "assignment": "board", "assignmentShape": "matrix", "depthBound": 4, "branching": 4, "truncated": false,
  "nodes": [
    { "id": 0, "level": 0, "label": "", "choice": "first call", "firstStep": 2, "endStep": 258, "kind": "explored", "kindStep": 259 },
    { "id": 1, "parent": 0, "level": 1, "label": "0", "choice": "col = 0", "firstStep": 11, "endStep": 161, "kind": "explored", "kindStep": 162 },
    { "id": 2, "parent": 1, "level": 2, "label": "0", "choice": "col = 0", "firstStep": 16, "endStep": 16, "kind": "rejected", "kindStep": 16 }
  ]
} ]
```

Collections appear in the heap as `"kind": "collection"` with `elements` (or `entries` for maps),
a `role` and, for stacks, `top` (`"first"` or `"last"`). User node objects carry a `role`
(`"list-node"` / `"tree-node"`) and their `links` (`["next"]`, `["left", "right"]`). Each step lists the
collection calls made since the previous step:

```json
"operations": [ { "ref": 60, "type": "ArrayDeque", "method": "push", "args": [ { "display": "'('" } ],
                  "line": 9, "kind": "push" } ]
```

and `analysis.structures` gives each collection's calls with their cost:

```json
"structures": [ { "variable": "count", "type": "HashMap", "role": "map",
                  "operations": [ { "method": "put", "count": 6, "cost": "O(1) average" } ], "note": "..." } ]
```

Tree and list algorithms add `measures`, e.g. `{ "label": "Height h of this tree", "value": "2" }`.

Events: `CALL` (first line of a method just called), `LINE` (line about to run), `RETURN`
(method returning; `returnValue` present unless void), `EXCEPTION` (uncaught; last step).
`outputLength` is how many characters of `execution.stdout` existed at that step.

## `POST /api/growth`

Same request. Runs the program at growing input sizes (see [complexity.md](complexity.md#3-measuring-growth)).

```json
{
  "execution": { "status": "SUCCESS", "...": "same shape as /api/execute (compile result)" },
  "measured": "the array arr",
  "reason": null,
  "points": [ { "n": 4, "operations": 15, "ms": 0 }, { "n": 8, "operations": 45, "ms": 0 } ],
  "fits": [
    { "curve": "n²", "logScale": -0.69, "error": 0.19 },
    { "curve": "1.62ⁿ", "logScale": 0.4, "error": 0.31, "base": 1.62 }
  ],
  "slope": 1.99,
  "stopped": "stopped after 2,100,225 operations at n = 2048"
}
```

`reason` explains an empty result: the program reads the keyboard, has no input in the code to scale,
or stopped too early. `fits` holds every curve, best first; `operations ≈ e^logScale · f(n)`.

## `GET /api/examples`

The Example menu: `[{ "id", "title", "group", "code", "stdin"?, "algorithms" }]`, read from
`backend/src/main/resources/examples/`. To add an example, add a `.java` file there and a line in
`index.json`; `ExamplesTest` then checks it compiles, traces and is recognised as exactly `algorithms`.

## `GET /api/health`

`{ "status": "UP", "javaVersion": "21..." }`

## Analysis inside a trace (`analysis`)

| Field | Content |
|---|---|
| `algorithms` | recognised algorithms: name, category, method, line, best/average/worst/space, evidence, worst-case bound for this n, measured facts |
| `estimates` | static estimates for the other methods: `time`, `space`, `recurrence`, `rule`, `steps`, `legend` |
| `input` | n (length of the largest array) and its name |
| `structures` | collections used, with each operation's count and cost |
| `searchTrees` | state-space trees, recursion trees (`memo`/`recursion`) and branch-and-bound trees (`bnb`) with the step each node appears |
| `dpTables` | DP tables with their fill order |
