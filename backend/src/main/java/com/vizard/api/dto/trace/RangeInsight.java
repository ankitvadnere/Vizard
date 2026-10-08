package com.vizard.api.dto.trace;

/**
 * The part of an array the current method is working on, e.g. {@code low..high} in binary
 * search, or {@code left..right} in one recursive call of merge sort. {@code from > to} means
 * the range is empty.
 */
public record RangeInsight(String array, long ref, String fromVariable, String toVariable, int from, int to) {
}
