package com.vizard.api.dto.trace;

/**
 * A loop the current line is inside.
 *
 * @param kind      "for", "for-each", "while" or "do"
 * @param line      line of the loop header
 * @param variable  the loop variable for for/for-each loops, else null
 * @param iteration 1-based count of the current iteration
 */
public record LoopInsight(String kind, int line, String variable, int iteration) {
}
