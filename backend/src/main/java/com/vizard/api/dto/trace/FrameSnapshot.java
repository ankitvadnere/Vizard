package com.vizard.api.dto.trace;

import java.util.List;

/**
 * One method call on the call stack.
 *
 * @param className  e.g. "Main" or "Main$Node"
 * @param methodName e.g. "main", "factorial", "&lt;init&gt;" for constructors
 * @param line       line this frame is currently at
 * @param variables  parameters and locals in scope at this point (plus "this" for instance methods)
 */
public record FrameSnapshot(String className, String methodName, int line, List<VariableSnapshot> variables) {
}
