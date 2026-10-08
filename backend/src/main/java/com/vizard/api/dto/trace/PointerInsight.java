package com.vizard.api.dto.trace;

/**
 * An int variable used as an index into an array, and where it points now.
 * index may be -1 or equal to the array length (one step outside the array).
 */
public record PointerInsight(String array, long ref, String variable, int index) {
}
