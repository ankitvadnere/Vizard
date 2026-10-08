// Wraps Monaco so the rest of the app never touches the Monaco API directly.

const MONACO_BASE = "https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min";
const MARKER_OWNER = "vizard";

let editor = null;
let errorDecorations = null;
let executionDecorations = null;
const changeListeners = new Set();

/** Loads Monaco from the CDN and creates the editor. Resolves when ready. */
export function initEditor(container, initialCode, shortcuts) {
    // Monaco's web workers can't load cross-origin directly; this proxy fixes that.
    window.MonacoEnvironment = {
        getWorkerUrl() {
            const bootstrap = `self.MonacoEnvironment = { baseUrl: "${MONACO_BASE}/" };
importScripts("${MONACO_BASE}/vs/base/worker/workerMain.js");`;
            return `data:text/javascript;charset=utf-8,${encodeURIComponent(bootstrap)}`;
        },
    };

    return new Promise((resolve) => {
        window.require.config({ paths: { vs: `${MONACO_BASE}/vs` } });
        window.require(["vs/editor/editor.main"], () => {
            const monaco = window.monaco;
            editor = monaco.editor.create(container, {
                value: initialCode,
                language: "java",
                theme: "vs-dark",
                fontFamily: "JetBrains Mono, Consolas, monospace",
                fontSize: 14,
                lineHeight: 22,
                minimap: { enabled: false },
                automaticLayout: true,
                scrollBeyondLastLine: false,
                tabSize: 4,
                renderLineHighlight: "none", // the execution line has its own highlight
                glyphMargin: true,
                stickyScroll: { enabled: false }, // pinned headers could cover the line being executed
                padding: { top: 10 },
            });
            errorDecorations = editor.createDecorationsCollection();
            executionDecorations = editor.createDecorationsCollection();

            const { KeyMod, KeyCode } = monaco;
            editor.addCommand(KeyMod.CtrlCmd | KeyCode.Enter, shortcuts.run);
            editor.addCommand(KeyMod.CtrlCmd | KeyMod.Shift | KeyCode.Enter, shortcuts.trace);

            editor.onDidChangeModelContent(() => {
                clearDiagnostics();
                changeListeners.forEach((listener) => listener());
            });
            resolve(editor);
        });
    });
}

export function getCode() {
    return editor ? editor.getValue() : "";
}

export function setCode(code) {
    if (editor) {
        editor.setValue(code);
        editor.setScrollTop(0);
    }
}

/** Called whenever the code changes (typing, paste, or loading an example). */
export function onCodeChange(listener) {
    changeListeners.add(listener);
}

/** Shows compile / policy problems as squiggles in the editor. */
export function showProblems(problems) {
    if (!editor) return;
    const monaco = window.monaco;
    const model = editor.getModel();
    const markers = problems
        .filter((p) => p.line > 0)
        .map((p) => {
            const line = Math.min(p.line, model.getLineCount());
            const column = Math.max(p.column ?? 1, 1);
            return {
                startLineNumber: line,
                startColumn: column,
                endLineNumber: line,
                endColumn: Math.max(column + 1, model.getLineMaxColumn(line)),
                message: p.message,
                severity: p.severity === "WARNING"
                    ? monaco.MarkerSeverity.Warning
                    : monaco.MarkerSeverity.Error,
            };
        });
    monaco.editor.setModelMarkers(model, MARKER_OWNER, markers);
}

/** Highlights the whole line where a runtime exception was thrown. */
export function markRuntimeErrorLine(line) {
    if (!editor || !line) return;
    errorDecorations.set([{
        range: new window.monaco.Range(line, 1, line, 1),
        options: {
            isWholeLine: true,
            className: "runtime-error-line",
            glyphMarginClassName: "runtime-error-glyph",
        },
    }]);
    editor.revealLineInCenterIfOutsideViewport(line);
}

/**
 * Marks the line the current step is on.
 * kind: "next" (about to run), "return" (method returning), "error" (exception thrown here)
 */
export function highlightExecutionLine(line, kind = "next") {
    if (!editor || !line) return;
    const styles = {
        next: { className: "exec-line", glyphMarginClassName: "exec-glyph" },
        return: { className: "exec-line-return", glyphMarginClassName: "exec-glyph-return" },
        error: { className: "runtime-error-line", glyphMarginClassName: "runtime-error-glyph" },
    };
    executionDecorations.set([{
        range: new window.monaco.Range(line, 1, line, 1),
        options: { isWholeLine: true, ...styles[kind] },
    }]);
    editor.revealLineInCenterIfOutsideViewport(line);
}

export function clearExecutionLine() {
    executionDecorations?.clear();
}

export function clearDiagnostics() {
    if (!editor) return;
    window.monaco.editor.setModelMarkers(editor.getModel(), MARKER_OWNER, []);
    errorDecorations.clear();
}

export function goToLine(line, column = 1) {
    if (!editor || !line) return;
    editor.revealLineInCenter(line);
    editor.setPosition({ lineNumber: line, column: Math.max(column, 1) });
    editor.focus();
}
