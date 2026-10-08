package com.vizard.execution.algorithms;

import java.util.function.LongUnaryOperator;

/** Closed-form worst-case operation counts, evaluated for a concrete n. */
enum BoundFormula {
    HALF_SQUARE("n(n−1)/2", n -> n * (n - 1) / 2),
    LINEAR("n", n -> n),
    TWO_LOG("2(⌊log₂n⌋ + 1)", n -> n <= 0 ? 0 : 2L * (floorLog2(n) + 1)),
    MERGE("n⌈log₂n⌉ − 2^⌈log₂n⌉ + 1", n -> {
        if (n <= 1) {
            return 0;
        }
        long c = ceilLog2(n);
        return n * c - (1L << c) + 1;
    });

    private final String text;
    private final LongUnaryOperator function;

    BoundFormula(String text, LongUnaryOperator function) {
        this.text = text;
        this.function = function;
    }

    String text() {
        return text;
    }

    long apply(long n) {
        return function.applyAsLong(n);
    }

    static long floorLog2(long n) {
        return 63 - Long.numberOfLeadingZeros(n);
    }

    static long ceilLog2(long n) {
        return n <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(n - 1);
    }
}
