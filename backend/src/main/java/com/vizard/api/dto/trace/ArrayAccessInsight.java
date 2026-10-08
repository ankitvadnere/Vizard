package com.vizard.api.dto.trace;

/**
 * One array element used by the current line.
 *
 * @param array    how the source names the array, e.g. "arr"
 * @param ref      heap id of the array
 * @param text     the access as written, e.g. "arr[j + 1]"
 * @param write    the line assigns to this element
 * @param compared the access is part of the line's condition
 */
public record ArrayAccessInsight(String array, long ref, int index, String text, boolean write, boolean compared) {
}
