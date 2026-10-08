package com.vizard.execution.algorithms;

import java.util.List;
import java.util.Set;

/**
 * One recognised algorithm, before the input size is known.
 *
 * @param traits facts about this particular implementation that change its complexity,
 *               e.g. "early-exit" (bubble sort), "shrinking" (inner loop shrinks each pass),
 *               "recursive" (binary search)
 */
public record Detection(Algorithm algorithm, String method, int line, List<String> evidence, Set<String> traits) {

    public static final String EARLY_EXIT = "early-exit";
    public static final String SHRINKING = "shrinking";
    public static final String RECURSIVE = "recursive";

    public boolean has(String trait) {
        return traits.contains(trait);
    }
}
