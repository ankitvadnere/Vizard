# Vizard

Interactive Java code execution, step-by-step debugging and algorithm visualization, built as a
Design and Analysis of Algorithms course project.

> Status: **Milestone 1** — write Java in the browser, compile and run it safely on the backend, see the output and errors.

## How it works (Milestone 1)

```
Browser (Monaco editor)
   │  POST /api/execute  { code, stdin }
   ▼
Spring Boot
   ├─ SourceAnalyzer     JavaParser → AST, find the class with main()
   ├─ SafetyPolicy       reject files, network, threads, reflection, System.exit...
   ├─ JavaCompilerService  javax.tools compiler, in-process, annotation processing off
   ├─ LocalProcessSandbox  separate JVM: timeout, -Xmx, output cap, empty env & temp dir
   └─ RunOutcomeClassifier success / runtime error (with line) / timeout / OOM / output flood
   ▼
ExecutionResponse JSON → Output, Problems, editor markers, status bar
```

## Project structure

```
vizard/
├── backend/                        Spring Boot (Maven)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/vizard/
│       │   ├── VizardApplication.java
│       │   ├── api/                ExecutionController, GlobalExceptionHandler, dto/
│       │   ├── config/             ExecutionProperties (limits)
│       │   └── execution/
│       │       ├── ExecutionService.java       pipeline orchestration
│       │       ├── RunOutcomeClassifier.java
│       │       ├── analysis/       SourceAnalyzer, SafetyPolicy, SourceAnalysis
│       │       ├── compile/        JavaCompilerService, CompilationResult
│       │       └── sandbox/        ExecutionSandbox (interface), LocalProcessSandbox, ...
│       ├── main/resources/application.properties
│       └── test/java/com/vizard/execution/ExecutionServiceTest.java
└── frontend/                       Plain HTML/CSS/JS, served by the backend
    ├── index.html
    ├── css/styles.css
    └── js/  main.js, api.js, editor.js, outputPanel.js, examples.js
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

Open http://localhost:8080. Run from the `backend` folder: the frontend is served from `../frontend`.

## Test

```powershell
cd backend
mvn test
```

## API

`POST /api/execute`

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

`GET /api/health` → `{ "status": "UP", "javaVersion": "21..." }`

## Execution limits

Configured in `application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `vizard.execution.timeout-ms` | 5000 | Wall-clock limit per run |
| `vizard.execution.max-heap-mb` | 128 | `-Xmx` of the program's JVM |
| `vizard.execution.max-output-bytes` | 65536 | stdout cap; the program is stopped when exceeded |
| `vizard.execution.max-concurrent-runs` | 2 | Simultaneous runs |

## Supported Java (Milestone 1)

One source file with a `public static void main(String[] args)`; any number of classes and methods.
Imports allowed from `java.util`, `java.util.function`, `java.util.stream`, `java.math`.
Not allowed: file I/O, network, processes, threads, reflection, `System.exit`, native methods.

## Security model and known limitations

User code never runs inside the Spring Boot process. It runs in a separate JVM with a timeout,
heap cap, output cap, one visible CPU, an empty environment and a throwaway working directory.
The AST-based `SafetyPolicy` rejects dangerous APIs before compiling.

This is a **development sandbox for local use**, not a hardened one: on Windows it cannot block
file or network access at the OS level, and native (off-heap) memory is not capped. The
`ExecutionSandbox` interface exists so a container-based sandbox (Docker with `--network none`,
read-only filesystem, cgroup CPU/memory limits) can replace `LocalProcessSandbox` without other changes.
Do not expose this server to the internet.
