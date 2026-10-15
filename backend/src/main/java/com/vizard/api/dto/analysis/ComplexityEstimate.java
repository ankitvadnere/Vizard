package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * A static estimate for a method that is not a recognised algorithm: read from its loops, calls and
 * recursion, never from running it. Always an upper bound for the worst case, never a proof.
 *
 * @param method     the method's name
 * @param line       where it starts
 * @param time       e.g. "O(n²)"; null when Vizard could not form an estimate (steps say why)
 * @param space      extra space (recursion depth and arrays it allocates), or null
 * @param recurrence "T(n) = 2T(n/2) + O(n)" for recursive methods, else null
 * @param rule       how the recurrence was solved, e.g. "Master theorem, case 2", else null
 * @param steps      the reasoning, one line per loop, call or recurrence; indented by nesting
 * @param legend     what each letter means: "n = arr.length"
 */
public record ComplexityEstimate(String method, int line, String time, String space, String recurrence, String rule,
                                 List<String> steps, List<String> legend) {
}
