// All communication with the Spring Boot backend lives here.
// The page is served by the backend itself, so relative URLs work on any port.

const API_BASE = "/api";

function unreachable(message) {
    return {
        status: "INTERNAL_ERROR",
        success: false,
        message,
        stdout: "",
        stderr: "",
        problems: [],
    };
}

async function post(path, body) {
    const response = await fetch(`${API_BASE}${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    return response.json();
}

/** Runs the program at full speed. Resolves to an ExecutionResponse. */
export async function executeCode(code, stdin) {
    try {
        return await post("/execute", { code, stdin });
    } catch {
        return unreachable("Could not reach the Vizard backend. Is it running?");
    }
}

/** Runs the program under the debugger. Resolves to { execution, steps, truncated, maxSteps }. */
export async function traceCode(code, stdin) {
    try {
        const trace = await post("/trace", { code, stdin });
        // Validation errors come back as a plain ExecutionResponse.
        if (!trace.execution) {
            return { execution: trace, steps: [], truncated: false };
        }
        return trace;
    } catch {
        return {
            execution: unreachable("Could not reach the Vizard backend. Is it running?"),
            steps: [],
            truncated: false,
        };
    }
}

export async function checkHealth() {
    try {
        const response = await fetch(`${API_BASE}/health`);
        return response.ok ? await response.json() : null;
    } catch {
        return null;
    }
}
