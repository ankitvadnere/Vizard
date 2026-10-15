package com.vizard.api.dto.growth;

/**
 * One run of the program at input size n.
 *
 * @param n          the input size
 * @param operations loop iterations plus calls of the program's own methods
 * @param ms         wall-clock time of main in that run
 */
public record GrowthPoint(int n, long operations, long ms) {
}
