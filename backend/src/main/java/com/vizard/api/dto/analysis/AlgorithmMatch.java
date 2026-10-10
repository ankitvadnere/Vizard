package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * An algorithm Vizard recognised in the program by its structure.
 *
 * @param id       stable identifier, e.g. "bubble-sort"
 * @param category "Sorting" or "Searching"
 * @param method   the method that implements it
 * @param line     line where that method starts
 * @param evidence why it was recognised, in plain words
 * @param bound    worst-case operation count for this input; null if not applicable
 * @param measures facts measured from this run that the complexity depends on (tree height...); may be empty
 */
public record AlgorithmMatch(
        String id,
        String name,
        String category,
        String method,
        int line,
        List<String> evidence,
        ComplexityInfo complexity,
        OperationBound bound,
        List<Measure> measures
) {
}
