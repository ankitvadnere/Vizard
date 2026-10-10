package com.vizard.execution.algorithms;

import com.vizard.api.dto.analysis.AlgorithmMatch;
import com.vizard.api.dto.analysis.ComplexityInfo;
import com.vizard.api.dto.analysis.Measure;
import com.vizard.api.dto.analysis.OperationBound;

import java.util.List;

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
        return describe(d, n, StructureFacts.NONE);
    }

    /**
     * @param n     input size, or null when the program used no arrays
     * @param facts sizes of the trees and lists the run built
     */
    public static AlgorithmMatch describe(Detection d, Integer n, StructureFacts facts) {
        ComplexityInfo complexity;
        OperationBound bound;
        List<Measure> measures = List.of();
        boolean recursive = d.has(Detection.RECURSIVE);

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
                complexity = new ComplexityInfo("O(1)", "O(log n)", "O(log n)",
                        recursive ? "O(log n)" : "O(1)", null,
                        "Needs a sorted array. Every step halves the range low..high, so at most ⌊log₂n⌋ + 1 "
                                + "steps are needed."
                                + (recursive ? " Each recursive call adds a stack frame, hence O(log n) space." : ""));
                bound = bound(COMPARISONS, "element comparisons at most (up to 2 per halving)",
                        BoundFormula.TWO_LOG, n, "per search");
            }
            case BST_SEARCH, BST_INSERT, BST_DELETE -> {
                String what = switch (d.algorithm()) {
                    case BST_SEARCH -> "A search";
                    case BST_INSERT -> "An insertion";
                    default -> "A deletion";
                };
                complexity = new ComplexityInfo("O(1)", "O(log n)", "O(n)", recursive ? "O(h)" : "O(1)", null,
                        what + " follows one path from the root, so it costs O(h) for a tree of height h. "
                                + "Balanced, h is about log₂n; keys inserted in sorted order make a chain with "
                                + "h = n − 1, the O(n) worst case."
                                + (recursive ? " Each level adds a call to the stack, hence O(h) space." : ""));
                bound = null;
                measures = treeMeasures(facts);
            }
            case PREORDER, INORDER, POSTORDER -> {
                complexity = new ComplexityInfo("O(n)", "O(n)", "O(n)", "O(h)", null,
                        "Every node is visited exactly once, whatever the tree's shape. The recursion keeps one "
                                + "call per level: O(h) space, O(log n) when balanced, O(n) for a chain."
                                + (d.algorithm() == Algorithm.INORDER
                                ? " On a binary search tree, inorder visits the keys in sorted order." : ""));
                bound = null;
                measures = treeMeasures(facts);
            }
            case LEVEL_ORDER -> {
                complexity = new ComplexityInfo("O(n)", "O(n)", "O(n)", "O(w)", null,
                        "Every node enters and leaves the queue once. The queue holds at most about one level "
                                + "at a time: O(w) for the widest level w, which is up to about n/2 in a complete tree.");
                bound = null;
                measures = treeMeasures(facts);
            }
            case LIST_REVERSAL -> {
                complexity = new ComplexityInfo("O(n)", "O(n)", "O(n)", recursive ? "O(n)" : "O(1)", null,
                        recursive
                                ? "One call per node, each O(1); the n calls on the stack make the space O(n)."
                                : "One pass, each node's link turned around once, using only three pointers: O(1) "
                                + "extra space.");
                bound = null;
                measures = facts.listNodes() > 0
                        ? List.of(new Measure("Nodes n", Integer.toString(facts.listNodes())))
                        : List.of();
            }
            default -> throw new IllegalStateException("Unknown algorithm " + d.algorithm());
        }

        Algorithm a = d.algorithm();
        return new AlgorithmMatch(a.id(), a.displayName(), a.category(), d.method(), d.line(), d.evidence(),
                complexity, bound, measures);
    }

    /** n, the measured height h, and the height the same n would have if balanced. */
    private static List<Measure> treeMeasures(StructureFacts facts) {
        if (facts.treeNodes() == 0) {
            return List.of();
        }
        int balanced = 63 - Long.numberOfLeadingZeros(facts.treeNodes()); // ⌊log₂n⌋
        return List.of(
                new Measure("Nodes n", Integer.toString(facts.treeNodes())),
                new Measure("Height h of this tree", Integer.toString(facts.treeHeight())),
                new Measure("Height if balanced, ⌊log₂n⌋", Integer.toString(balanced)),
                new Measure("Height of a chain, n − 1", Integer.toString(facts.treeNodes() - 1)));
    }

    private static OperationBound bound(String metric, String description, BoundFormula formula, Integer n,
                                        String scope) {
        Long value = n == null ? null : formula.apply(n);
        return new OperationBound(metric, description, formula.text(), value, scope);
    }
}
