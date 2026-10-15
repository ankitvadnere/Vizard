package com.vizard.api.dto.growth;

/**
 * How well one growth curve explains the measurements.
 *
 * @param curve     "1", "log n", "n", "n log n", "n²", "n³", "2ⁿ" or "n!"
 * @param logScale  ln of the constant c in operations ≈ c · f(n) (the frontend redraws the curve)
 * @param error     root-mean-square error of ln(operations) against ln(c · f(n)): 0 is a perfect fit
 * @param base      for the exponential curve bⁿ, the base b fitted to the data (1.62 for Fibonacci); else null
 */
public record GrowthFit(String curve, double logScale, double error, Double base) {
}
