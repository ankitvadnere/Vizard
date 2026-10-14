package com.vizard.api.dto.trace;

/** An edge from → to; weight is its display value, or null for an unweighted graph. */
public record GraphEdge(String from, String to, String weight) {
}
