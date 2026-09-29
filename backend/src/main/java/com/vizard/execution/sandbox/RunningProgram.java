package com.vizard.execution.sandbox;

/**
 * A user program that has been started in the sandbox. Tracing needs this handle
 * because the debugger talks to the program while it runs.
 */
public interface RunningProgram extends AutoCloseable {

    /** Milliseconds left before the time limit; 0 when it has passed. */
    long remainingMs();

    /** Brings the time limit forward so at most {@code ms} milliseconds remain. */
    void limitRemainingTo(long ms);

    /**
     * Waits until the program exits or the time limit passes (then kills it),
     * and returns what happened. Safe to call more than once.
     */
    SandboxResult awaitCompletion();

    /** Kills the program if it is still running. */
    @Override
    void close();
}
