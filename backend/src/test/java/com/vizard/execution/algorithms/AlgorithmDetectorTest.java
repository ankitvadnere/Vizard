package com.vizard.execution.algorithms;

import com.github.javaparser.StaticJavaParser;
import com.vizard.api.dto.Example;
import com.vizard.examples.ExampleCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Recognition by structure, without Spring or a JVM launch. */
class AlgorithmDetectorTest {

    private static List<Detection> detect(String code) {
        return AlgorithmDetector.detect(StaticJavaParser.parse(code));
    }

    @Test
    void everyExampleIsRecognisedAsItsAlgorithmAndNothingElse() {
        for (Example example : new ExampleCatalog().all()) {
            List<String> found = detect(example.code()).stream().map(d -> d.algorithm().id()).toList();
            List<String> expected = example.algorithm() == null ? List.of() : List.of(example.algorithm());
            assertThat(found).as(example.id()).isEqualTo(expected);
        }
    }

    @Test
    void bubbleSortWithoutEarlyExitHasQuadraticBestCase() {
        Example bubble = example("bubble-sort");
        Detection d = detect(bubble.code()).get(0);
        assertThat(d.has(Detection.EARLY_EXIT)).isFalse();
        assertThat(d.has(Detection.SHRINKING)).isTrue();
        assertThat(AlgorithmCatalog.describe(d, 4).complexity().best()).isEqualTo("O(n²)");
        assertThat(AlgorithmCatalog.describe(d, 4).bound().value()).isEqualTo(6L);
    }

    @Test
    void bubbleSortWithSwappedFlagHasLinearBestCase() {
        Detection d = detect("""
                class Main {
                    static void bubbleSort(int[] a) {
                        boolean swapped = true;
                        int n = a.length;
                        while (swapped) {
                            swapped = false;
                            for (int j = 1; j < n; j++) {
                                if (a[j - 1] > a[j]) {
                                    int t = a[j - 1];
                                    a[j - 1] = a[j];
                                    a[j] = t;
                                    swapped = true;
                                }
                            }
                            n--;
                        }
                    }
                }
                """).get(0);
        assertThat(d.algorithm()).isEqualTo(Algorithm.BUBBLE_SORT);
        assertThat(d.has(Detection.EARLY_EXIT)).isTrue();
        assertThat(d.has(Detection.SHRINKING)).isTrue(); // n-- in the outer loop
        assertThat(AlgorithmCatalog.describe(d, 4).complexity().best()).isEqualTo("O(n)");
    }

    @Test
    void recursiveBinarySearchNeedsLogarithmicSpace() {
        Detection d = detect("""
                class Main {
                    static int find(int[] a, int lo, int hi, int x) {
                        if (lo > hi) return -1;
                        int mid = (lo + hi) >>> 1;
                        if (a[mid] == x) return mid;
                        if (a[mid] < x) return find(a, mid + 1, hi, x);
                        return find(a, lo, mid - 1, x);
                    }
                }
                """).get(0);
        assertThat(d.algorithm()).isEqualTo(Algorithm.BINARY_SEARCH);
        assertThat(d.has(Detection.RECURSIVE)).isTrue();
        assertThat(AlgorithmCatalog.describe(d, 5).complexity().space()).isEqualTo("O(log n)");
    }

    @Test
    void lookalikesAreNotClaimed() {
        assertThat(detect("""
                class Main {
                    static int fib(int n) {
                        if (n < 2) return n;
                        return fib(n - 1) + fib(n - 2);
                    }
                    static int max(int[] a) {
                        int best = a[0];
                        for (int i = 0; i < a.length; i++) {
                            if (a[i] > best) best = a[i];
                        }
                        return best;
                    }
                    static void fill(int[][] g) {
                        for (int i = 0; i < g.length; i++)
                            for (int j = 0; j < g[i].length; j++)
                                if (g[i][j] == 0) g[i][j] = i * j;
                    }
                }
                """)).isEmpty();
    }

    @Test
    void boundFormulasMatchHandCalculations() {
        assertThat(BoundFormula.HALF_SQUARE.apply(4)).isEqualTo(6);
        assertThat(BoundFormula.HALF_SQUARE.apply(5)).isEqualTo(10);
        assertThat(BoundFormula.TWO_LOG.apply(7)).isEqualTo(6);   // 2 × (⌊log₂7⌋ + 1) = 2 × 3
        assertThat(BoundFormula.MERGE.apply(7)).isEqualTo(14);    // 7·3 − 8 + 1
        assertThat(BoundFormula.MERGE.apply(8)).isEqualTo(17);    // 8·3 − 8 + 1
        assertThat(BoundFormula.MERGE.apply(1)).isEqualTo(0);
    }

    private static Example example(String id) {
        return new ExampleCatalog().all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }
}
