package com.vizard.execution.sandbox;

/**
 * Raw outcome of running a program. Interpreting it (runtime error? OOM?) is
 * the job of {@code RunOutcomeClassifier}, not the sandbox.
 */
public record SandboxResult(
        String stdout,
        String stderr,
        Integer exitCode,
        boolean timedOut,
        boolean outputLimitExceeded,
        long durationMs
) {
}
