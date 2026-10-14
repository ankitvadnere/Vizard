package com.vizard.api.dto.trace;

/** A per-vertex value such as a distance: vertex label → display value. */
public record VertexValue(String vertex, String value) {
}
