package com.vizard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resource limits for running user programs. Bound from "vizard.execution.*"
 * in application.properties so they can be tuned without code changes.
 *
 * @param timeoutMs         wall-clock limit for a normal run
 * @param maxHeapMb         -Xmx of the program's JVM
 * @param maxOutputBytes    stdout cap; the program is stopped when exceeded
 * @param maxConcurrentRuns simultaneous runs/traces
 * @param traceTimeoutMs    wall-clock limit for a traced run (debugging is slower)
 * @param maxTraceSteps     steps recorded before tracing stops and the program just finishes
 */
@ConfigurationProperties(prefix = "vizard.execution")
public record ExecutionProperties(
        @DefaultValue("5000") long timeoutMs,
        @DefaultValue("128") int maxHeapMb,
        @DefaultValue("65536") int maxOutputBytes,
        @DefaultValue("2") int maxConcurrentRuns,
        @DefaultValue("15000") long traceTimeoutMs,
        @DefaultValue("3000") int maxTraceSteps
) {
}
