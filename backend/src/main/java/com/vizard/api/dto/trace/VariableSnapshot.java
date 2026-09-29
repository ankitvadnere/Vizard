package com.vizard.api.dto.trace;

/**
 * A named variable: a local, a method parameter ({@code argument = true}),
 * {@code this}, an object field, or a static field ("Main.count").
 */
public record VariableSnapshot(String name, String type, ValueSnapshot value, boolean argument) {
}
