package com.vizard.execution.growth;

import com.vizard.api.dto.growth.GrowthFit;
import com.vizard.api.dto.growth.GrowthPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Fits operations ≈ c · f(n) for the usual growth curves, in log space (so a curve is judged by
 * its shape, not dominated by the largest point), and picks the one with the smallest error.
 * A fit describes these measurements on this generated input; it is evidence, not a proof.
 */
public final class GrowthFitter {

    /** ln f(n) for each curve. */
    static final Map<String, DoubleUnaryOperator> CURVES = new LinkedHashMap<>();

    static {
        CURVES.put("1", n -> 0);
        CURVES.put("log n", n -> Math.log(Math.log(n) / Math.log(2)));
        CURVES.put("n", Math::log);
        CURVES.put("n log n", n -> Math.log(n) + Math.log(Math.log(n) / Math.log(2)));
        CURVES.put("n²", n -> 2 * Math.log(n));
        CURVES.put("n³", n -> 3 * Math.log(n));
        CURVES.put("n!", GrowthFitter::lnFactorial);
        CURVES.put("n · n!", n -> Math.log(n) + lnFactorial(n));
    }

    private GrowthFitter() {
    }

    public static List<GrowthFit> fit(List<GrowthPoint> points) {
        List<GrowthPoint> usable = usable(points);
        if (usable.size() < 3) {
            return List.of();
        }
        List<GrowthFit> fits = new ArrayList<>();
        for (Map.Entry<String, DoubleUnaryOperator> c : CURVES.entrySet()) {
            double sum = 0;
            for (GrowthPoint p : usable) {
                sum += Math.log(p.operations()) - c.getValue().applyAsDouble(p.n());
            }
            double logC = sum / usable.size();
            double sq = 0;
            for (GrowthPoint p : usable) {
                double r = Math.log(p.operations()) - (logC + c.getValue().applyAsDouble(p.n()));
                sq += r * r;
            }
            fits.add(new GrowthFit(c.getKey(), logC, Math.sqrt(sq / usable.size()), null));
        }
        // bⁿ with the base fitted too: ln(ops) = ln c + n ln b is a straight line in n
        double mx = usable.stream().mapToDouble(GrowthPoint::n).average().orElse(0);
        double my = usable.stream().mapToDouble(p -> Math.log(p.operations())).average().orElse(0);
        double num = 0;
        double den = 0;
        for (GrowthPoint p : usable) {
            num += (p.n() - mx) * (Math.log(p.operations()) - my);
            den += (p.n() - mx) * (p.n() - mx);
        }
        double lnB = den == 0 ? 0 : num / den;
        if (lnB > Math.log(1.1)) {
            double b = Math.exp(lnB);
            double logC = my - lnB * mx;
            double sq = 0;
            for (GrowthPoint p : usable) {
                double r = Math.log(p.operations()) - (logC + lnB * p.n());
                sq += r * r;
            }
            // one more free parameter than the other curves: a small penalty keeps n² from losing to 1.01ⁿ
            double rms = Math.sqrt(sq / usable.size()) + 0.05;
            String label = String.format(java.util.Locale.ROOT, "%.2f", b).replaceAll("\\.?0+$", "") + "ⁿ";
            fits.add(new GrowthFit(label, logC, rms, b));
        }
        fits.sort(Comparator.comparingDouble(GrowthFit::error));
        return fits;
    }

    /** Slope of ln(ops) against ln(n) over the larger half of the sizes (lower-order terms fade there). */
    public static Double slope(List<GrowthPoint> points) {
        List<GrowthPoint> usable = usable(points);
        if (usable.size() < 3) {
            return null;
        }
        List<GrowthPoint> tail = usable.subList(usable.size() / 2 - (usable.size() % 2 == 0 ? 1 : 0), usable.size());
        if (tail.size() < 2) {
            tail = usable;
        }
        double mx = 0;
        double my = 0;
        for (GrowthPoint p : tail) {
            mx += Math.log(p.n());
            my += Math.log(p.operations());
        }
        mx /= tail.size();
        my /= tail.size();
        double num = 0;
        double den = 0;
        for (GrowthPoint p : tail) {
            double dx = Math.log(p.n()) - mx;
            num += dx * (Math.log(p.operations()) - my);
            den += dx * dx;
        }
        return den == 0 ? null : num / den;
    }

    /** Tiny sizes are mostly constant overhead: leave out n < 8 when at least four larger sizes remain. */
    static List<GrowthPoint> usable(List<GrowthPoint> points) {
        List<GrowthPoint> all = points.stream().filter(p -> p.n() >= 2 && p.operations() > 0).toList();
        List<GrowthPoint> large = all.stream().filter(p -> p.n() >= 8).toList();
        return large.size() >= 4 ? large : all;
    }

    static double lnFactorial(double n) {
        double s = 0;
        for (int i = 2; i <= (int) n; i++) {
            s += Math.log(i);
        }
        return s;
    }
}
