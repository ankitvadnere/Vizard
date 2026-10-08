package com.vizard.api.dto.trace;

import java.util.List;

/**
 * What a step <i>means</i>, computed by combining the recorded state with the program's AST.
 * The frontend draws these facts; it never interprets Java itself.
 *
 * @param condition the condition evaluated on this line, if any
 * @param accesses  array elements this line reads or writes
 * @param changes   array cells whose value changed since the previous step
 * @param swap      two cells that just exchanged values, if this step completed a swap
 * @param pointers  int variables currently used as indexes into arrays (i, j, low, mid...)
 * @param loops     loops the current line is inside, outermost first, with their iteration count
 */
public record StepInsight(
        ConditionInsight condition,
        List<ArrayAccessInsight> accesses,
        List<ArrayChangeInsight> changes,
        SwapInsight swap,
        List<PointerInsight> pointers,
        List<LoopInsight> loops
) {
}
