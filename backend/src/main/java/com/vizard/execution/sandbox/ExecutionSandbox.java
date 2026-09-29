package com.vizard.execution.sandbox;

/**
 * Runs compiled user code in isolation from the Vizard server.
 *
 * <p>Milestone 1 uses {@link LocalProcessSandbox} (a separate, limited JVM process).
 * A stronger implementation (e.g. a Docker container with no network and a read-only
 * filesystem) can be dropped in later without touching the rest of the code.
 */
public interface ExecutionSandbox {

    /** Starts the program and returns immediately. */
    RunningProgram start(SandboxRequest request);

    /** Starts the program and waits for it to finish. */
    default SandboxResult run(SandboxRequest request) {
        try (RunningProgram program = start(request)) {
            return program.awaitCompletion();
        }
    }
}
