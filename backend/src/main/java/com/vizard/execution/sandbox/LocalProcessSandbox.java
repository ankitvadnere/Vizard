package com.vizard.execution.sandbox;

import com.vizard.config.ExecutionProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the user program in a <b>separate JVM process</b> with:
 * <ul>
 *   <li>a wall-clock timeout, after which the process tree is killed</li>
 *   <li>a maximum heap size (-Xmx)</li>
 *   <li>one visible CPU and a small GC footprint</li>
 *   <li>capped stdout (process is killed when exceeded) and truncated stderr</li>
 *   <li>an empty temporary working directory and an emptied environment</li>
 * </ul>
 *
 * <p>Honest limitations (documented in the README): on Windows this cannot block file or
 * network access at the OS level, and native (non-heap) memory is not capped. Those gaps are
 * narrowed by {@code SafetyPolicy} and closed properly by a container sandbox later.
 */
@Component
public class LocalProcessSandbox implements ExecutionSandbox {

    private final ExecutionProperties props;

    public LocalProcessSandbox(ExecutionProperties props) {
        this.props = props;
    }

    @Override
    public SandboxResult run(SandboxRequest request) {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(request))
                .directory(request.workDir().toFile());

        // Do not leak the server's environment variables (paths, tokens, ...) to user code.
        Map<String, String> env = builder.environment();
        String systemRoot = env.get("SystemRoot"); // Windows needs this for the JVM to start cleanly
        env.clear();
        if (systemRoot != null) {
            env.put("SystemRoot", systemRoot);
        }

        long start = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the program process", e);
        }

        Runnable kill = () -> killTree(process);
        BoundedStreamCollector out = new BoundedStreamCollector(process.getInputStream(), props.maxOutputBytes(), kill);
        BoundedStreamCollector err = new BoundedStreamCollector(process.getErrorStream(), props.maxOutputBytes(), () -> { });
        Thread outThread = Thread.ofPlatform().daemon().name("vizard-stdout").start(out);
        Thread errThread = Thread.ofPlatform().daemon().name("vizard-stderr").start(err);

        writeStdin(process, request.stdin());

        boolean finished;
        try {
            finished = process.waitFor(props.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            killTree(process);
        }

        try {
            process.waitFor(2, TimeUnit.SECONDS); // let the OS release the process
            outThread.join(1000);
            errThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        boolean outputExceeded = out.limitExceeded();
        Integer exitCode = process.isAlive() ? null : process.exitValue();

        return new SandboxResult(out.text(), err.text(), exitCode,
                !finished && !outputExceeded, outputExceeded, durationMs);
    }

    private List<String> buildCommand(SandboxRequest request) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-Xmx" + props.maxHeapMb() + "m");
        cmd.add("-Xms16m");
        cmd.add("-XX:+UseSerialGC");            // one GC thread, small footprint
        cmd.add("-XX:ActiveProcessorCount=1");  // JVM sizes its internals for one CPU
        cmd.add("-XX:TieredStopAtLevel=1");     // faster startup for short programs
        cmd.add("-XX:-UsePerfData");            // no shared-memory perf files
        cmd.add("-Xshare:auto");
        cmd.add("-XX:MaxJavaStackTraceDepth=64"); // keep StackOverflowError traces readable
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.add("-Djava.io.tmpdir=" + request.workDir());
        cmd.add("-cp");
        cmd.add(request.classesDir().toString());
        cmd.add(request.mainClass());
        return cmd;
    }

    /** The same java binary that runs Vizard, so versions always match the compiler. */
    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static void writeStdin(Process process, String stdin) {
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // Program exited before reading its input; that's fine.
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
