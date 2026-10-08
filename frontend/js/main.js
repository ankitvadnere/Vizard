// Entry point: wires the editor, the two run modes, playback and the panels together.
//
// Modes:
//   edit  – normal editing; Run shows output.
//   trace – a recorded execution is loaded; the controls move through its steps.
//           Editing the code leaves trace mode, because the steps no longer match it.

import { executeCode, traceCode, checkHealth } from "./api.js";
import {
    initEditor, getCode, setCode, onCodeChange, showProblems, markRuntimeErrorLine,
    clearDiagnostics, goToLine, highlightExecutionLine, clearExecutionLine,
} from "./editor.js";
import {
    initConsole, showRunning, renderResult, clearOutput, showOutputAtStep, showFullOutput,
    setStdin, getStdin,
} from "./outputPanel.js";
import { EXAMPLES } from "./examples.js";
import { PlaybackController } from "./playback/PlaybackController.js";
import { PlaybackBar } from "./playback/PlaybackBar.js";
import { VariableVisualizer } from "./visualizations/VariableVisualizer.js";
import { VisualizationPanel } from "./visualizations/VisualizationPanel.js";

const runButton = document.getElementById("run-button");
const traceButton = document.getElementById("trace-button");
const traceLabel = traceButton.querySelector(".label");
const exampleSelect = document.getElementById("example-select");
const backendStatus = document.getElementById("status-backend");

const playback = new PlaybackController();
const playbackBar = new PlaybackBar(playback, { onExit: () => exitTrace() });
const variables = new VariableVisualizer(
    document.getElementById("variables"), document.getElementById("frame-label"));
const visualization = new VisualizationPanel();

let busy = false;
let trace = null; // the loaded TraceResponse while in trace mode
let lastRenderedIndex = -1; // to animate only when moving forward by exactly one step

// ---------- Run (full speed) ----------

async function run() {
    if (busy) return;
    exitTrace();
    await withBusy("Compiling and running…", async () => {
        const result = await executeCode(getCode(), getStdin());
        showRunOutcome(result);
    });
}

// ---------- Step through ----------

async function stepThrough() {
    if (busy) return;
    exitTrace();
    await withBusy("Recording every step…", async () => {
        const response = await traceCode(getCode(), getStdin());
        const execution = response.execution;

        if (response.steps.length === 0) {
            showRunOutcome(execution);
            return;
        }

        trace = response;
        const note = response.truncated
            ? `Showing the first ${response.steps.length} steps. After that the program ran without recording.`
            : `${response.steps.length} steps recorded. Use the controls below the editor, or the arrow keys.`;
        renderResult(execution, jumpToProblem, note);
        showProblems(execution.problems ?? []);

        lastRenderedIndex = -1;
        playback.load(response.steps);
        playbackBar.show();
        renderStep();
    });
}

function renderStep() {
    const step = playback.current;
    if (!trace || !step) return;

    const kind = step.event === "EXCEPTION" ? "error" : step.event === "RETURN" ? "return" : "next";
    highlightExecutionLine(step.line, kind);
    variables.render(step, playback.previous);
    visualization.render(step, { animate: playback.index === lastRenderedIndex + 1 && lastRenderedIndex >= 0 });
    lastRenderedIndex = playback.index;

    // At the last step, also show anything printed afterwards and any stack trace.
    const execution = trace.execution;
    if (playback.atEnd) {
        showFullOutput(execution);
    } else {
        showOutputAtStep(execution.stdout ?? "", step.outputLength, "");
    }
}

function exitTrace(message) {
    if (!trace) return;
    trace = null;
    playback.clear();
    playbackBar.hide();
    clearExecutionLine();
    variables.showEmpty(message);
    visualization.showEmpty(message);
}

// ---------- Shared ----------

function showRunOutcome(result) {
    renderResult(result, jumpToProblem);
    showProblems(result.problems ?? []);
    if (result.runtimeError?.line) {
        markRuntimeErrorLine(result.runtimeError.line);
    }
}

function jumpToProblem(problem) {
    goToLine(problem.line, problem.column);
}

async function withBusy(label, task) {
    busy = true;
    runButton.disabled = true;
    traceButton.disabled = true;
    traceLabel.textContent = "Working…";
    clearDiagnostics();
    showRunning(label);
    try {
        await task();
    } finally {
        busy = false;
        runButton.disabled = false;
        traceButton.disabled = false;
        traceLabel.textContent = "Step through";
    }
}

function loadExample(id) {
    const example = EXAMPLES.find((e) => e.id === id);
    if (!example) return;
    setCode(example.code); // also leaves trace mode via onCodeChange
    setStdin(example.stdin ?? "");
    clearOutput();
    variables.showEmpty();
    visualization.showEmpty();
}

function populateExamples() {
    for (const e of EXAMPLES) {
        const option = document.createElement("option");
        option.value = e.id;
        option.textContent = e.title;
        exampleSelect.append(option);
    }
    exampleSelect.addEventListener("change", () => loadExample(exampleSelect.value));
}

async function start() {
    initConsole();
    populateExamples();
    variables.showEmpty();
    visualization.showEmpty();

    await initEditor(document.getElementById("editor"), EXAMPLES[0].code, { run, trace: stepThrough });

    runButton.addEventListener("click", run);
    traceButton.addEventListener("click", stepThrough);
    document.getElementById("clear-output").addEventListener("click", () => {
        exitTrace();
        clearOutput();
    });
    playback.onChange(renderStep);
    onCodeChange(() => exitTrace("The code changed. Press Step through to record the new version."));

    const health = await checkHealth();
    backendStatus.textContent = health
        ? `Backend connected (Java ${health.javaVersion})`
        : "Backend not reachable";
}

start();
