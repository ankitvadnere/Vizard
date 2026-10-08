package com.vizard.api.dto.trace;

/** Two cells of one array that exchanged values (detected within the last three steps). */
public record SwapInsight(long ref, int first, int second) {
}
