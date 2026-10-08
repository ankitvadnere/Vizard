package com.vizard.execution;

import com.vizard.execution.insight.CodeModel;

import java.nio.file.Path;
import java.util.List;

/**
 * A program that parsed, passed the safety policy and compiled; ready to run or trace.
 *
 * @param sourceFileName   e.g. "Main.java" (stack traces refer to it)
 * @param mainClassName    fully-qualified class with main(String[])
 * @param userClassNames   fully-qualified top-level classes declared by the user
 * @param launcherClassName Vizard's generated entry point (tracing only, else null)
 * @param codeModel        line-level facts from the AST used to explain steps (tracing only, else null)
 */
public record PreparedProgram(
        Path classesDir,
        Path runDir,
        String sourceFileName,
        String mainClassName,
        List<String> userClassNames,
        String launcherClassName,
        long compileTimeMs,
        CodeModel codeModel
) {
}
