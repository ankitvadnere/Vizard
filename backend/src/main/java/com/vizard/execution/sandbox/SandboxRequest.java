package com.vizard.execution.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * @param classesDir    directory containing the compiled .class files
 * @param mainClass     fully-qualified name of the class to run
 * @param workDir       empty directory the program runs in
 * @param stdin         text for System.in (may be empty)
 * @param timeoutMs     wall-clock limit for this run
 * @param extraJvmArgs  additional JVM options (the debugger agent when tracing)
 */
public record SandboxRequest(Path classesDir, String mainClass, Path workDir, String stdin,
                             long timeoutMs, List<String> extraJvmArgs) {
}
