package com.vizard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resource limits for running user programs. Bound from "vizard.execution.*"
 * in application.properties so they can be tuned without code changes.
 */
@ConfigurationProperties(prefix = "vizard.execution")
public record ExecutionProperties(
        @DefaultValue("5000") long timeoutMs,
        @DefaultValue("128") int maxHeapMb,
        @DefaultValue("65536") int maxOutputBytes,
        @DefaultValue("2") int maxConcurrentRuns
) {
}
