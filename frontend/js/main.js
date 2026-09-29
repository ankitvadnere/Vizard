// Entry point: wires the editor, controls, and panels together.

import { executeCode, checkHealth } from "./api.js";
import {
    initEditor, getCode, setCode, showProblems, markRuntimeErrorLine, clearDiagnostics, goToLine,
} from "./editor.js";
import { showRunning, renderResult, clearOutput } from "./outputPanel.js";
import { EXAMPLES } from "./examples.js";

const runButton = document.getElementById("run-button");
const runLabel = runButton.querySelector(".run-label");
const exampleSelect = document.getElementById("example-select");
const stdinBox = document.getElementById("stdin");
const backendStatus = document.getElementById("status-backend");

let running = false;

async function run() {
    if (running) return;
    running = true;
    runButton.disabled = true;
    runLabel.textContent = "Running";
    clearDiagnostics();
    showRunning();

    try {
        const result = await executeCode(getCode(), stdinBox.value);
        renderResult(result, (p) => goToLine(p.line, p.column));
        showProblems(result.problems ?? []);
        if (result.runtimeError?.line) {
            markRuntimeErrorLine(result.runtimeError.line);
        }
    } finally {
        running = false;
        runButton.disabled = false;
        runLabel.textContent = "Run";
    }
}

function loadExample(id) {
    const example = EXAMPLES.find((e) => e.id === id);
    if (!example) return;
    setCode(example.code);
    stdinBox.value = example.stdin ?? "";
    clearOutput();
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
    populateExamples();
    await initEditor(document.getElementById("editor"), EXAMPLES[0].code, run);

    runButton.addEventListener("click", run);
    document.getElementById("clear-output").addEventListener("click", clearOutput);

    const health = await checkHealth();
    backendStatus.textContent = health
        ? `Backend connected (Java ${health.javaVersion})`
        : "Backend not reachable";
}

start();
