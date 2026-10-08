package com.vizard.api.dto.trace;

import java.util.List;

/** Cells of one array whose values differ from the previous step. */
public record ArrayChangeInsight(long ref, List<Integer> indices) {
}
