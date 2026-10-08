// The console area (Output / Problems / Input tabs), the result banner and the status bar.

const els = {
    output: document.getElementById("output"),
    banner: document.getElementById("result-banner"),
    problems: document.getElementById("problems"),
    problemCount: document.getElementById("problem-count"),
    pill: document.getElementById("status-pill"),
    compile: document.getElementById("status-compile"),
    run: document.getElementById("status-run"),
    exit: document.getElementById("status-exit"),
    tabs: [...document.querySelectorAll(".console-pane .tab")],
    panels: [...document.querySelectorAll(".console-pane .tab-panel")],
    stdin: document.getElementById("stdin"),
    inputDot: document.getElementById("input-dot"),
};

// status → [banner title, visual kind]
const STATUS_VIEW = {
    SUCCESS: ["Finished", "ok"],
    COMPILATION_ERROR: ["Compilation error", "err"],
    UNSUPPORTED_FEATURE: ["Not supported yet", "warn"],
    RUNTIME_ERROR: ["Runtime error", "err"],
    TIMEOUT: ["Timed out", "err"],
    MEMORY_LIMIT_EXCEEDED: ["Memory limit exceeded", "err"],
    OUTPUT_LIMIT_EXCEEDED: ["Output limit exceeded", "err"],
    INVALID_REQUEST: ["Invalid request", "warn"],
    SERVER_BUSY: ["Busy", "warn"],
    INTERNAL_ERROR: ["Vizard error", "err"],
};

const PLACEHOLDER = "Press Run for the output, or Step through to follow the program line by line.";

export function initConsole() {
    for (const tab of els.tabs) {
        tab.addEventListener("click", () => selectTab(tab.dataset.tab));
    }
    const updateDot = () => { els.inputDot.hidden = els.stdin.value.trim() === ""; };
    els.stdin.addEventListener("input", updateDot);
    updateDot();
    showPlaceholder();
}

export function selectTab(name) {
    for (const tab of els.tabs) tab.setAttribute("aria-selected", String(tab.dataset.tab === name));
    for (const panel of els.panels) panel.hidden = panel.dataset.panel !== name;
}

export function setStdin(text) {
    els.stdin.value = text;
    els.inputDot.hidden = text.trim() === "";
}

export function getStdin() {
    return els.stdin.value;
}

export function showRunning(label) {
    els.pill.textContent = label;
    els.pill.dataset.state = "running";
    els.compile.textContent = "";
    els.run.textContent = "";
    els.exit.textContent = "";
}

/**
 * Shows how a run ended. `note` is an extra line under the banner (e.g. the step limit).
 * Returns true if there were problems to look at.
 */
export function renderResult(result, onProblemClick, note = "") {
    const [title, kind] = STATUS_VIEW[result.status] ?? ["Unknown result", "err"];

    els.pill.textContent = title;
    els.pill.dataset.state = kind;

    els.banner.hidden = false;
    els.banner.dataset.kind = kind;
    els.banner.textContent = bannerText(result);
    if (note) {
        const span = document.createElement("span");
        span.className = "note";
        span.textContent = note;
        els.banner.append(span);
    }

    showFullOutput(result);
    const problems = result.problems ?? [];
    renderProblems(problems, onProblemClick);
    selectTab(problems.length > 0 ? "problems" : "output");

    els.compile.textContent = result.compileTimeMs ? `Compile ${result.compileTimeMs} ms` : "";
    els.run.textContent = result.runTimeMs ? `Run ${result.runTimeMs} ms` : "";
    els.exit.textContent = result.exitCode != null ? `Exit code ${result.exitCode}` : "";
    return problems.length > 0;
}

/** Complete output of a run: stdout, then stderr in red. */
export function showFullOutput(result) {
    writeOutput(result.stdout ?? "", result.stderr ?? "", result.status === "SUCCESS"
        ? "The program finished without printing anything."
        : "No output.");
}

/** Output as it was at one step: the first `length` characters of stdout. */
export function showOutputAtStep(stdout, length, stderr) {
    writeOutput(stdout.slice(0, length), stderr ?? "", "Nothing printed yet.");
}

export function clearOutput() {
    els.banner.hidden = true;
    showPlaceholder();
    renderProblems([], null);
    els.pill.textContent = "Ready";
    els.pill.dataset.state = "idle";
    els.compile.textContent = els.run.textContent = els.exit.textContent = "";
}

function showPlaceholder() {
    els.output.replaceChildren(placeholder(PLACEHOLDER));
}

function writeOutput(stdout, stderr, emptyMessage) {
    els.output.replaceChildren();
    if (!stdout && !stderr) {
        els.output.append(placeholder(emptyMessage));
        return;
    }
    if (stdout) els.output.append(document.createTextNode(stdout));
    if (stderr) {
        const span = document.createElement("span");
        span.className = "stderr";
        span.textContent = (stdout && !stdout.endsWith("\n") ? "\n" : "") + stderr;
        els.output.append(span);
    }
    els.output.scrollTop = els.output.scrollHeight;
}

function placeholder(text) {
    const span = document.createElement("span");
    span.className = "placeholder";
    span.textContent = text;
    return span;
}

function bannerText(result) {
    const err = result.runtimeError;
    if (err) {
        const where = err.line ? ` (line ${err.line})` : "";
        const msg = err.message ? `: ${err.message}` : "";
        return `${err.exceptionType}${msg}${where}`;
    }
    return result.message ?? "";
}

function renderProblems(problems, onProblemClick) {
    els.problems.replaceChildren();
    els.problemCount.textContent = String(problems.length);
    els.problemCount.dataset.has = String(problems.length > 0);

    if (problems.length === 0) {
        const li = document.createElement("li");
        li.className = "empty";
        li.textContent = "No problems.";
        els.problems.append(li);
        return;
    }

    for (const p of problems) {
        const li = document.createElement("li");
        li.dataset.severity = p.severity;
        li.tabIndex = 0;

        const loc = document.createElement("span");
        loc.className = "loc";
        loc.textContent = p.line ? `Line ${p.line}:${p.column || 1}` : "—";

        const msg = document.createElement("span");
        msg.className = "msg";
        msg.textContent = p.message;

        li.append(loc, msg);
        if (onProblemClick && p.line) {
            li.addEventListener("click", () => onProblemClick(p));
            li.addEventListener("keydown", (e) => { if (e.key === "Enter") onProblemClick(p); });
        }
        els.problems.append(li);
    }
}
