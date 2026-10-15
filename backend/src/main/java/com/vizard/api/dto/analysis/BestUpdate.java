package com.vizard.api.dto.analysis;

/** The best solution so far improved at {@code step} to {@code value}, found at tree node {@code node}. */
public record BestUpdate(int step, String value, Integer node) {
}
