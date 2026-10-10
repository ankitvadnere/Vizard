package com.vizard.api.dto.trace;

/** One key → value pair of a map, in the map's own iteration order. */
public record EntrySnapshot(ValueSnapshot key, ValueSnapshot value) {
}
