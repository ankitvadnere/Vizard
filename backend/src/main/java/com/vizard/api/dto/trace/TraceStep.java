package com.vizard.api.dto.trace;

import java.util.List;
import java.util.Map;

/**
 * The complete program state at one step. The frontend renders a step without
 * knowing anything about Java: same step in, same picture out.
 *
 * <p>Events:
 * <ul>
 *   <li>{@code CALL}: first line of a method that was just called (also the very first step)</li>
 *   <li>{@code LINE}: this line is about to execute</li>
 *   <li>{@code RETURN}: the method is returning ({@code returnValue} holds the result)</li>
 *   <li>{@code EXCEPTION}: an uncaught exception was thrown here; the program ends</li>
 * </ul>
 *
 * @param index         0-based step number
 * @param line          highlighted source line
 * @param depth         call-stack depth (1 = inside main)
 * @param stack         user frames, innermost first; stack.get(0) is the current method
 * @param statics       static fields of the user's classes
 * @param heap          arrays/objects reachable from the variables, keyed by id
 * @param outputLength  how many characters of stdout had been printed at this step
 */
public record TraceStep(
        int index,
        String event,
        int line,
        int depth,
        List<FrameSnapshot> stack,
        List<VariableSnapshot> statics,
        Map<String, HeapObjectSnapshot> heap,
        int outputLength,
        ValueSnapshot returnValue,
        String exceptionType,
        String exceptionMessage
) {
}
