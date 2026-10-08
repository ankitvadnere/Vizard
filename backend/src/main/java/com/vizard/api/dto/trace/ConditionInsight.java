package com.vizard.api.dto.trace;

/**
 * A condition on the current line, e.g. text "arr[j] > arr[j + 1]", explanation "5 > 2", result true.
 *
 * @param kind        "if", "while", "for" or "do"
 * @param explanation the condition with known values substituted; equals text when nothing is known
 * @param result      true/false, or null when it could not be determined (e.g. it threw)
 */
public record ConditionInsight(String kind, int line, String text, String explanation, Boolean result) {
}
