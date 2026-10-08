# Vizard

Interactive Java code execution, step-by-step debugging and algorithm visualization, built as a
Design and Analysis of Algorithms course project.

> Status: **Milestone 3** — step through Java forwards and backwards and *see* it run: arrays as boxes with
> index pointers, compared and changed cells, animated swaps, conditions with their values and result,
> loop iteration counters, and the call stack.

## How it works

```
Browser (Monaco editor)
   │  POST /api/execute  or  POST /api/trace   { code, stdin }
   ▼
Spring Boot
   ├─ SourceAnalyzer       JavaParser → AST, find the class with main()
   ├─ SafetyPolicy         reject files, network, threads, reflection, System.exit...
   ├─ JavaCompilerService  javax.tools compiler, in-process, annotation processing off
   ├─ LocalProcessSandbox  separate JVM: timeout, -Xmx, output cap, empty env & temp dir
   ├─ RunOutcomeClassifier success / runtime error (with line) / timeout / OOM / output flood
   └─ (trace only)
        JdiTraceSession   the sandboxed JVM starts paused and connects to Vizard's debugger (JDI);
                          a breakpoint on every line + method-exit watches record the full state:
                          call stack, locals, statics, reachable arrays/objects, output so far
        CodeModelBuilder  AST → where conditions, loops and array accesses are; which int
                          variables index which arrays
        TraceAnnotator    state + code model → per-step insight: condition values and result,
                          accessed/changed cells, swaps, pointers, loop iterations
   ▼
JSON → editor highlight, Variables, Call stack, Output, playback controls
```

### Why a debugger instead of rewriting the code

Tracing uses the Java Debug Interface, the same API IntelliJ's debugger uses, rather than
inserting `trace()` calls into the source. The user's code runs unmodified, so the recorded values
are exactly what the JVM computed; there is no re-implementation of Java's scoping rules that
could disagree with the compiler. The cost is speed (about 1,500 steps per second), which is why
traces are capped. The AST from JavaParser provides the meaning on top of the state.

### How a condition's result is decided

For `if (arr[j] > arr[j + 1])` Vizard shows `5 > 2` and `true`. The values come from a small
evaluator that reads the recorded state; it never runs code, and it treats anything with side
effects (method calls, `i++`, assignments) as unknown. The true/false result is taken from **what
actually happened next**: if the next line in the same method call is inside the branch, the
condition was true. That works even for `if (isPrime(n))`, which the evaluator can't compute.
For `if`/`while` the evaluator is used first (Vizard stops exactly where those conditions are
evaluated); for a `for` header it's the other way round, because that stop happens before the
loop's update runs.

## Project structure

```
vizard/
├── backend/                        Spring Boot (Maven)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/vizard/
│       │   ├── VizardApplication.java
│       │   ├── api/                ExecutionController, GlobalExceptionHandler, dto/, dto/trace/
│       │   ├── config/             ExecutionProperties (limits)
│       │   └── execution/
│       │       ├── ExecutionService.java       pipeline shared by Run and Step through
│       │       ├── PreparedProgram.java
│       │       ├── RunOutcomeClassifier.java
│       │       ├── analysis/       SourceAnalyzer, SafetyPolicy, SourceAnalysis
│       │       ├── compile/        JavaCompilerService, CompilationResult
│       │       ├── sandbox/        ExecutionSandbox, RunningProgram, LocalProcessSandbox, ...
│       │       ├── trace/          JdiTraceSession, HeapReader, SameLineLoops, TraceRunner, ...
│       │       └── insight/        CodeModelBuilder, CodeModel, Expr, ExpressionEvaluator, TraceAnnotator
│       ├── main/resources/application.properties
│       └── test/java/com/vizard/execution/   ExecutionServiceTest, TraceServiceTest, InsightTest,
│                                             insight/CodeModelBuilderTest
└── frontend/                       Plain HTML/CSS/JS, served by the backend
    ├── index.html
    ├── css/styles.css
    └── js/
        ├── main.js, api.js, editor.js, outputPanel.js, values.js, examples.js
        ├── playback/        PlaybackController, PlaybackBar, describeStep
        └── visualizations/  VisualizationPanel, ArrayVisualizer (SVG), InsightStrip,
                             CallStackVisualizer, VariableVisualizer
```

## Requirements

- **JDK 21** (a JDK, not a JRE — Vizard needs the compiler)
- **Maven 3.9+** (bundled with IntelliJ IDEA)
- Internet access in the browser (Monaco and fonts load from a CDN)

## Run

```powershell
cd backend
mvn spring-boot:run
```

Open http://localhost:18080. Run from the `backend` folder: the frontend is served from `../frontend`.

## Test

```powershell
cd backend
mvn test
```

## API

### `POST /api/execute`

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

### `POST /api/trace`

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

Events: `CALL` (first line of a method just called), `LINE` (line about to run), `RETURN`
(method returning; `returnValue` present unless void), `EXCEPTION` (uncaught; last step).
`outputLength` is how many characters of `execution.stdout` existed at that step.

### `GET /api/health`

`{ "status": "UP", "javaVersion": "21..." }`

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

## Supported Java (Milestone 1)

One source file with a `public static void main(String[] args)`; any number of classes and methods.
Imports allowed from `java.util`, `java.util.function`, `java.util.stream`, `java.math`.
Not allowed: file I/O, network, processes, threads, reflection, `System.exit`, native methods.

## Tracing limitations

- Loops written on one line (`for (...) sum += i;`) are stepped through every iteration (an extra
  breakpoint is placed on the loop's jump-back target), but they get no iteration counter.
- When a `for` loop ends, its variable is already out of scope, so the final check shows as
  `j < 3` is `false` rather than `3 < 3`.
- Pointers are detected from how variables are used (`arr[j]`, `mid = low + (high - low) / 2`,
  `i < arr.length`); an index variable used in other ways may not get a caret.
- At most 3000 steps are recorded; arrays show their first 100 elements and each step keeps up to 150 objects.
- The first step may be a class's static setup (`static int count = 0;`), which Java really runs before `main`.

## Security model and known limitations

User code never runs inside the Spring Boot process. It runs in a separate JVM with a timeout,
heap cap, output cap, one visible CPU, an empty environment and a throwaway working directory.
The AST-based `SafetyPolicy` rejects dangerous APIs before compiling.

This is a **development sandbox for local use**, not a hardened one: on Windows it cannot block
file or network access at the OS level, and native (off-heap) memory is not capped. The
`ExecutionSandbox` interface exists so a container-based sandbox (Docker with `--network none`,
read-only filesystem, cgroup CPU/memory limits) can replace `LocalProcessSandbox` without other changes.
Do not expose this server to the internet.
