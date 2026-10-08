package com.vizard.api.dto.analysis;

/** n for the complexity formulas: the length of the largest array the program worked on, and its name. */
public record InputSize(String name, int n) {
}
