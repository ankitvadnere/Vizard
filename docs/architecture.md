# Architecture

Vizard is two programs: a Spring Boot backend that compiles, runs, traces and analyses Java, and a
plain HTML/CSS/JavaScript frontend that draws what the backend recorded. The frontend never runs
Java and never guesses: every picture is a function of the recorded steps and of the static analysis
sent with them.

## The pipeline

```
Browser (Monaco editor)
   │  POST /api/execute | /api/trace | /api/growth   { code, stdin }
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
        CollectionReader  ArrayList, ArrayDeque, HashMap, TreeMap, PriorityQueue... read through
                          their internal fields (nothing runs inside the program)
        CollectionCalls   breakpoints on the call instructions of lines that use a collection, so
                          each push / poll / put the program makes is recorded with its arguments
        StructureRoles    how each collection was used (an ArrayDeque fed push/pop is a stack)
        CodeModelBuilder  AST → where conditions, loops and array accesses are; which int
                          variables index which arrays
        TraceAnnotator    state + code model → per-step insight: condition values and result,
                          accessed/changed cells, swaps, pointers, loop iterations, active range,
                          and running statistics (comparisons, swaps, reads, writes, calls, depth)
        AlgorithmDetector AST → recognised algorithms (by structure, not by name), including
        StructureAlgorithms BST search / insert / delete, traversals and list reversal →
        AlgorithmCatalog  best / average / worst / space, with worst-case counts for this run's n
                          and measured facts (tree height); StructureCosts: cost of each call made
        Graph/Search/Dp/Recursion/BnbTracker  per-step pictures of graphs, state-space trees, DP
                          tables, recursion trees and branch-and-bound trees
        ComplexityEstimator  AST → an estimated O(…) for methods that are not recognised algorithms
   └─ (growth only, POST /api/growth)
        GrowthInstrumenter the program rewritten: generated input of size n, a counter on every loop
                          iteration and call; run in the sandbox at n = 4, 8, 16, …
        GrowthFitter      operations ≈ c·f(n) fitted for 1, log n, n, n log n, n², n³, bⁿ, n!
   ▼
JSON → editor highlight, Variables, Call stack, Output, playback controls
```

## Three requests, one pipeline

| Request | What runs | What comes back |
|---|---|---|
| `POST /api/execute` (Run) | parse → safety check → compile → sandboxed JVM | output, status, runtime error line |
| `POST /api/trace` (Step through) | the same, with the JVM paused under Vizard's debugger | every step (state + insight), recognised algorithms, estimates, trees, tables |
| `POST /api/growth` (Measure growth) | the same checks, then a rewritten copy run at growing sizes | operation counts per size and the fitted curves |

All three share `ExecutionService.prepare` (parse, policy, entry point, compile), a semaphore that limits
simultaneous runs, and a throwaway temp directory deleted after every request.

## Static analysis and dynamic recording

Two kinds of knowledge meet in `TraceAnnotator`:

- **Static** (from the AST, before running): where conditions, loops and array accesses are
  (`CodeModelBuilder`); which methods are graph traversals, backtracking searches, DP tables,
  memoised recursion or branch and bound (`GraphModelBuilder`, `BacktrackModelBuilder`,
  `DpModelBuilder`, `BnbModelBuilder`); which algorithm each method is (`AlgorithmDetector`); an
  estimated complexity for the rest (`ComplexityEstimator`).
- **Dynamic** (from the debugger, while running): the call stack, every local, the heap objects reachable
  from them, output so far, and the collection calls made.

The annotator walks the steps once, evaluating the static facts against each recorded state: "this
condition is `5 > 2`, true", "this cell was written", "a new state-space node starts here". Trackers
that need history (graph visit order, search trees, DP fill order, branch-and-bound live lists) keep it
as they go, so the frontend gets complete trees and tables once, with the step at which each part
appears, and can draw step k without replaying anything.

## Why a debugger instead of rewriting the code

Tracing uses the Java Debug Interface, the same API IntelliJ's debugger uses, rather than
inserting `trace()` calls into the source. The user's code runs unmodified, so the recorded values
are exactly what the JVM computed; there is no re-implementation of Java's scoping rules that
could disagree with the compiler. The cost is speed (about 1,500 steps per second), which is why
traces are capped. The AST from JavaParser provides the meaning on top of the state.

The growth check is the one place where Vizard does rewrite code: it needs millions of operations at
large inputs, which a debugger cannot record quickly. The rewritten copy only adds a counter and swaps
the input; it never replaces what Step through shows.

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
│       │       ├── trace/          JdiTraceSession, HeapReader, CollectionReader, CollectionCalls,
│       │       │                   SameLineLoops, TraceRunner, ...
│       │       ├── insight/        CodeModelBuilder, CodeModel, Expr, ExpressionEvaluator,
│       │       │                   TraceAnnotator, StructureRoles, GraphModelBuilder, GraphTracker,
│       │       │                   BacktrackModelBuilder, SearchTracker, DpModelBuilder,
│       │       │                   DpTracker, RecursionTracker, BnbModelBuilder, BnbTracker
│       │       ├── estimate/       ComplexityEstimator, Cost (big-O algebra)
│       │       ├── growth/         GrowthInstrumenter, GrowthFitter
│       │       └── algorithms/     AlgorithmDetector, StructureAlgorithms, GraphAlgorithms,
│       │                           BacktrackAlgorithms, SearchCosts, DpAlgorithms, DpCosts, BnbAlgorithms,
│       │                           AlgorithmCatalog, StructureCosts,
│       │                           StructureFacts, Algorithm, Detection
│       ├── main/java/com/vizard/examples/   ExampleCatalog
│       ├── main/resources/examples/         index.json + one .java file per example
│       ├── main/resources/application.properties
│       └── test/java/com/vizard/   execution/ExecutionServiceTest, TraceServiceTest, InsightTest,
│                                   StatisticsTest, CollectionsTest, StructureStatisticsTest,
│                                   GraphTraceTest, SearchTraceTest, DpTraceTest, BnbTraceTest,
│                                   insight/CodeModelBuilderTest, insight/GraphModelBuilderTest,
│                                   BacktrackModelBuilderTest, DpModelBuilderTest, BnbModelBuilderTest,
│                                   trace/NodeShapeTest, algorithms/AlgorithmDetectorTest,
│                                   StructureAlgorithmsTest, GraphAlgorithmsTest,
│                                   BacktrackAlgorithmsTest, DpAlgorithmsTest, BnbAlgorithmsTest, examples/ExamplesTest,
│                                   SecurityTest, GrowthTest, estimate/ComplexityEstimatorTest,
│                                   growth/GrowthFitterTest, growth/GrowthInstrumenterTest
└── frontend/                       Plain HTML/CSS/JS, served by the backend
    ├── index.html
    ├── css/styles.css
    └── js/
        ├── main.js, api.js, editor.js, outputPanel.js, values.js, tabs.js, fullscreen.js, shortcuts.js
        ├── playback/        PlaybackController, PlaybackBar, describeStep
        └── visualizations/  VisualizationPanel, ArrayVisualizer (SVG), StructureVisualizer,
                             StackVisualizer, QueueVisualizer, HeapVisualizer, MapVisualizer,
                             LinkedListVisualizer, TreeVisualizer, GraphVisualizer,
                             SearchTreeVisualizer, BoardVisualizer, DpTableVisualizer,
                             InsightStrip, StatsBar,
                             ComplexityPanel, GrowthChart, CallStackVisualizer, VariableVisualizer,
                             visible.js, svg.js
```

## Design decisions

- **Recognition by structure, never by name.** A method called `bubbleSort` that does something
  else is not called bubble sort; a bubble sort called `foo` is.
- **Known complexity only from a catalogue; everything else is labelled an estimate.** Recognised
  algorithms get their textbook best/average/worst case. Other methods get a static estimate with its
  reasoning, always marked "Estimate", and the growth check measures rather than claims.
- **Every number is exact for the run.** Counters come from the recorded steps; the frontend only
  displays them.
- **Insight is a bonus.** If analysis trips over unusual code, the trace still works without it
  (each analysis is wrapped so it cannot fail a request).
- **The sandbox is an interface.** `ExecutionSandbox` can be replaced by a container-based one
  without touching the rest.
