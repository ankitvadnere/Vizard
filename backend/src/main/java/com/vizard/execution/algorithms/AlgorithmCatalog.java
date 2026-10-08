package com.vizard.execution.algorithms;

import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.api.dto.analysis.ComplexityInfo;
import com.vizard.api.dto.analysis.OperationBound;

/**
 * Textbook complexity for each recognisable algorithm.
 *
 * <p>This is a <b>known-algorithm mapping</b>, not a proof: Vizard reports these figures only for
 * code it recognised, and adjusts them for implementation details it can see (for example
 * whether bubble sort stops early).
 */
public final class AlgorithmCatalog {

    private static final String COMPARISONS = "comparisons";

    private AlgorithmCatalog() {
    }

    /** @param n input size, or null when the program used no arrays */
    public static AlgorithmMatch describe(Detection d, Integer n) {
        ComplexityInfo complexity;
        OperationBound bound;

        switch (d.algorithm()) {
            case BUBBLE_SORT -> {
                boolean early = d.has(Detection.EARLY_EXIT);
                complexity = new ComplexityInfo(early ? "O(n)" : "O(n²)", "O(n²)", "O(n²)", "O(1)", true,
                        early
                                ? "Stops as soon as a pass makes no swaps, so already-sorted input needs one pass: O(n)."
                                : "There is no early-exit check, so every pass runs even when the array is already "
                                + "sorted. The best case is therefore O(n²) too; add a 'swapped' flag to make it O(n).");
                bound = d.has(Detection.SHRINKING)
                        ? bound(COMPARISONS, early ? "element comparisons at most" : "element comparisons (exactly, for any input)",
                        BoundFormula.HALF_SQUARE, n, "program")
                        : null;
            }
            case SELECTION_SORT -> {
                complexity = new ComplexityInfo("O(n²)", "O(n²)", "O(n²)", "O(1)", false,
                        "Always scans the whole unsorted part to find the minimum, whatever the input. "
                                + "Long-distance swaps can reorder equal elements, so it is not stable.");
                bound = bound(COMPARISONS, "element comparisons (exactly, for any input)",
                        BoundFormula.HALF_SQUARE, n, "program");
            }
            case INSERTION_SORT -> {
                complexity = new ComplexityInfo("O(n)", "O(n²)", "O(n²)", "O(1)", true,
                        "Sorted input needs one comparison per element (best case); reverse-sorted input "
                                + "shifts every element all the way left (worst case).");
                bound = bound(COMPARISONS, "element comparisons at most", BoundFormula.HALF_SQUARE, n, "program");
            }
            case MERGE_SORT -> {
                complexity = new ComplexityInfo("O(n log n)", "O(n log n)", "O(n log n)", "O(n)", true,
                        "Always splits in half, so there are about log₂n levels of recursion with O(n) merging "
                                + "work on each, whatever the input. The temporary arrays need O(n) extra space.");
                bound = bound(COMPARISONS, "element comparisons at most", BoundFormula.MERGE, n, "program");
            }
            case QUICK_SORT -> {
                complexity = new ComplexityInfo("O(n log n)", "O(n log n)", "O(n²)", "O(log n)", false,
                        "Fast when the pivot splits the array evenly. If the pivot is always the smallest or "
                                + "largest element (e.g. sorted input with the last element as pivot) every split is "
                                + "lopsided and it degrades to O(n²). The space is the recursion stack: O(log n) on "
                                + "average, O(n) in that worst case.");
                bound = bound(COMPARISONS, "element comparisons at most", BoundFormula.HALF_SQUARE, n, "program");
            }
            case LINEAR_SEARCH -> {
                complexity = new ComplexityInfo("O(1)", "O(n)", "O(n)", "O(1)", null,
                        "Checks elements one by one, so it works on unsorted data. Best case: the target is first.");
                bound = bound(COMPARISONS, "element comparisons at most", BoundFormula.LINEAR, n, "per search");
            }
            case BINARY_SEARCH -> {
                boolean recursive = d.has(Detection.RECURSIVE);
                complexity = new ComplexityInfo("O(1)", "O(log n)", "O(log n)",
                        recursive ? "O(log n)" : "O(1)", null,
                        "Needs a sorted array. Every step halves the range low..high, so at most ⌊log₂n⌋ + 1 "
                                + "steps are needed."
                                + (recursive ? " Each recursive call adds a stack frame, hence O(log n) space." : ""));
                bound = bound(COMPARISONS, "element comparisons at most (up to 2 per halving)",
                        BoundFormula.TWO_LOG, n, "per search");
            }
            default -> throw new IllegalStateException("Unknown algorithm " + d.algorithm());
        }

        Algorithm a = d.algorithm();
        return new AlgorithmMatch(a.id(), a.displayName(), a.category(), d.method(), d.line(), d.evidence(),
                complexity, bound);
    }

    private static OperationBound bound(String metric, String description, BoundFormula formula, Integer n,
                                        String scope) {
        Long value = n == null ? null : formula.apply(n);
        return new OperationBound(metric, description, formula.text(), value, scope);
    }
}
