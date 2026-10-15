package com.vizard.execution.growth;

import com.vizard.api.dto.growth.GrowthFit;
import com.vizard.api.dto.growth.GrowthPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** The fitter picks the curve that produced the counts, even with lower-order terms added. */
class GrowthFitterTest {

    private static List<GrowthPoint> doubling(IntToLongFunction ops) {
        List<GrowthPoint> points = new ArrayList<>();
        for (int n = 4; n <= 2048; n *= 2) {
            points.add(new GrowthPoint(n, ops.applyAsLong(n), 1));
        }
        return points;
    }

    private static String best(List<GrowthPoint> points) {
        return GrowthFitter.fit(points).get(0).curve();
    }

    @Test
    void polynomialAndLogCurves() {
        assertThat(best(doubling(n -> 3L * n + 7))).isEqualTo("n");
        assertThat(best(doubling(n -> (long) n * (n - 1) / 2 + n))).isEqualTo("n²");
        assertThat(best(doubling(n -> (long) n * n * n / 6))).isEqualTo("n³");
        assertThat(best(doubling(n -> (long) (n * (Math.log(n) / Math.log(2))) + 2L * n))).isEqualTo("n log n");
        assertThat(best(doubling(n -> (long) (Math.log(n) / Math.log(2)) + 1))).isEqualTo("log n");
        assertThat(best(doubling(n -> 5))).isEqualTo("1");
        assertThat(GrowthFitter.slope(doubling(n -> (long) n * n))).isBetween(1.95, 2.05);
    }

    @Test
    void exponentialGetsItsOwnBase() {
        List<GrowthPoint> fib = new ArrayList<>();
        long a = 1;
        long b = 1;
        for (int n = 1; n <= 30; n++) {
            long c = a + b;
            a = b;
            b = c;
            if (n >= 8 && n % 4 == 0) {
                fib.add(new GrowthPoint(n, 2 * b - 1, 1)); // calls of naive fib(n)
            }
        }
        GrowthFit fit = GrowthFitter.fit(fib).get(0);
        assertThat(fit.base()).isNotNull();
        assertThat(fit.base()).isBetween(1.58, 1.66);
    }

    @Test
    void factorial() {
        List<GrowthPoint> perm = new ArrayList<>();
        long f = 1;
        for (int n = 1; n <= 10; n++) {
            f *= n;
            if (n >= 4) {
                perm.add(new GrowthPoint(n, n * f, 1));
            }
        }
        assertThat(best(perm)).isEqualTo("n · n!");
    }

    @Test
    void tooFewPointsGiveNoFit() {
        assertThat(GrowthFitter.fit(List.of(new GrowthPoint(4, 10, 1), new GrowthPoint(8, 20, 1)))).isEmpty();
    }
}
