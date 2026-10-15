package com.vizard.api.dto.growth;

import com.vizard.api.dto.ExecutionResponse;

import java.util.List;

/**
 * Result of POST /api/growth: the program run at growing input sizes.
 *
 * @param execution compile result, or the reason no measurement was possible
 * @param measured  what was made bigger, e.g. "the array arr"; null if nothing could be
 * @param reason    why there is no measurement (no input in the code, it reads the keyboard...), else null
 * @param points    one per run, in order of n
 * @param fits      every candidate curve, best first
 * @param slope     the slope of log(operations) against log(n) over the larger sizes: about k for nᵏ
 * @param stopped   why the series ended ("reached the limit of 20,000,000 operations at n = 32"...)
 */
public record GrowthResponse(ExecutionResponse execution, String measured, String reason, List<GrowthPoint> points,
                             List<GrowthFit> fits, Double slope, String stopped) {

    public static GrowthResponse failed(ExecutionResponse execution, String reason) {
        return new GrowthResponse(execution, null, reason, List.of(), List.of(), null, null);
    }
}
