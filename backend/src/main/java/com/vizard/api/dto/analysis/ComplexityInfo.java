package com.vizard.api.dto.analysis;

/**
 * Textbook complexity of a recognised algorithm.
 *
 * @param stable null when stability doesn't apply (searching)
 * @param note   what makes this version's complexity what it is, e.g. "no early exit"
 */
public record ComplexityInfo(String best, String average, String worst, String space, Boolean stable, String note) {
}
