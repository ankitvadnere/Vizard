// All communication with the Spring Boot backend lives here.
// The page is served by the backend itself, so relative URLs work.

const API_BASE = "/api";

/**
 * Sends code to the backend and returns the ExecutionResponse JSON.
 * Network failures are converted to the same shape so the UI has one code path.
 */
export async function executeCode(code, stdin) {
    try {
        const response = await fetch(`${API_BASE}/execute`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ code, stdin }),
        });
        return await response.json();
    } catch (error) {
        return {
            status: "INTERNAL_ERROR",
            success: false,
            message: "Could not reach the Vizard backend. Is it running on port 8080?",
            stdout: "",
            stderr: "",
            problems: [],
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
