// Renders an ExecutionResponse into the Output, Problems and status bar areas.

const els = {
    output: document.getElementById("output"),
    banner: document.getElementById("result-banner"),
    problems: document.getElementById("problems"),
    problemCount: document.getElementById("problem-count"),
    pill: document.getElementById("status-pill"),
    compile: document.getElementById("status-compile"),
    run: document.getElementById("status-run"),
    exit: document.getElementById("status-exit"),
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

export function showRunning() {
    els.pill.textContent = "Compiling and running…";
    els.pill.dataset.state = "running";
    els.compile.textContent = "";
    els.run.textContent = "";
    els.exit.textContent = "";
}

export function renderResult(result, onProblemClick) {
    const [title, kind] = STATUS_VIEW[result.status] ?? ["Unknown result", "err"];

    els.pill.textContent = title;
    els.pill.dataset.state = kind;

    els.banner.hidden = false;
    els.banner.dataset.kind = kind;
    els.banner.textContent = bannerText(result);

    renderOutput(result);
    renderProblems(result.problems ?? [], onProblemClick);

    els.compile.textContent = result.compileTimeMs ? `Compile ${result.compileTimeMs} ms` : "";
    els.run.textContent = result.runTimeMs ? `Run ${result.runTimeMs} ms` : "";
    els.exit.textContent = result.exitCode != null ? `Exit code ${result.exitCode}` : "";
}

export function clearOutput() {
    els.banner.hidden = true;
    els.output.innerHTML = '<span class="placeholder">Press Run to compile and execute your program.</span>';
    renderProblems([], null);
    els.pill.textContent = "Ready";
    els.pill.dataset.state = "idle";
    els.compile.textContent = els.run.textContent = els.exit.textContent = "";
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

function renderOutput(result) {
    els.output.replaceChildren();
    const stdout = result.stdout ?? "";
    // stderr holds stack traces from runtime errors.
    const stderr = result.stderr ?? "";

    if (!stdout && !stderr) {
        const empty = document.createElement("span");
        empty.className = "placeholder";
        empty.textContent = result.status === "SUCCESS"
            ? "The program finished without printing anything."
            : "No output.";
        els.output.append(empty);
        return;
    }
    if (stdout) els.output.append(document.createTextNode(stdout));
    if (stderr) {
        const span = document.createElement("span");
        span.className = "stderr";
        span.textContent = (stdout && !stdout.endsWith("\n") ? "\n" : "") + stderr;
        els.output.append(span);
    }
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
