package com.vizard.api.dto.analysis;

/** A fact measured from this run, shown beside a complexity, e.g. "Height h" = "3". */
public record Measure(String label, String value) {
}
