package com.vizard.execution.trace;

import com.vizard.api.dto.trace.TraceStep;

import java.util.List;

/**
 * Steps captured by the debugger. {@code outputLength} in these steps is still a
 * byte count; {@link OutputOffsets} converts it to characters once stdout is known.
 *
 * @param truncated the step limit was reached and the rest of the program ran untraced
 */
public record TraceRecording(List<TraceStep> steps, boolean truncated) {
}
