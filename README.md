# Vizard

Interactive Java code execution, step-by-step debugging and algorithm visualization, built as a
Design and Analysis of Algorithms course project.

> Status: **Milestone 2** — run Java safely, then step through its execution forwards and backwards:
> highlighted line, variables, call stack and output at every step.

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
   └─ (trace only) JdiTraceSession
         the sandboxed JVM starts paused and connects to Vizard's debugger (JDI);
         a breakpoint on every line + method-exit watches record the full state:
         call stack, locals, statics, reachable arrays/objects, output so far
   ▼
JSON → editor highlight, Variables, Call stack, Output, playback controls
```

### Why a debugger instead of rewriting the code

Tracing uses the Java Debug Interface, the same API IntelliJ's debugger uses, rather than
inserting `trace()` calls into the source. The user's code runs unmodified, so the recorded values
are exactly what the JVM computed; there is no re-implementation of Java's scoping rules that
could disagree with the compiler. The cost is speed (about 1,500 steps per second), which is why
traces are capped. The AST from JavaParser is kept for the semantic layer in later milestones
(comparisons, swaps, loop iterations).

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
│       │       └── trace/          JdiTraceSession, HeapReader, TraceRunner, LauncherSource, ...
│       ├── main/resources/application.properties
│       └── test/java/com/vizard/execution/   ExecutionServiceTest, TraceServiceTest
└── frontend/                       Plain HTML/CSS/JS, served by the backend
    ├── index.html
    ├── css/styles.css
    └── js/
        ├── main.js, api.js, editor.js, outputPanel.js, values.js, examples.js
        ├── playback/        PlaybackController, PlaybackBar, describeStep
        └── visualizations/  VariableVisualizer, CallStackVisualizer
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

## Tracing limitations (Milestone 2)

- A loop written entirely on one line (`for (...) sum += i;`) shows as one step, because the
  debugger stops at the start of each line. Put the body on its own line to see every iteration.
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
