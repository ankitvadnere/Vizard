package com.vizard.api.dto.analysis;

/** Cell (row, col) of a table got a value, visible from {@code step} on. A 1-D table uses row 0. */
public record DpWrite(int row, int col, int step) {
}
