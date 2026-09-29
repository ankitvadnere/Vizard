package com.vizard.execution.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Drains a process stream on its own thread, keeping at most {@code limit} bytes.
 * When the limit is hit it calls {@code onLimitExceeded} once; for stdout that kills the
 * process (a print loop can't flood the server), for stderr it does nothing and the rest
 * is discarded (so a long StackOverflowError trace is still reported as a runtime error).
 */
class BoundedStreamCollector implements Runnable {

    private final InputStream in;
    private final int limit;
    private final Runnable onLimitExceeded;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile boolean limitExceeded;

    BoundedStreamCollector(InputStream in, int limit, Runnable onLimitExceeded) {
        this.in = in;
        this.limit = limit;
        this.onLimitExceeded = onLimitExceeded;
    }

    @Override
    public void run() {
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (limitExceeded) {
                    continue; // keep draining so the process never blocks on a full pipe
                }
                int room = limit - buffer.size();
                if (n > room) {
                    buffer.write(chunk, 0, Math.max(room, 0));
                    limitExceeded = true;
                    onLimitExceeded.run();
                } else {
                    buffer.write(chunk, 0, n);
                }
            }
        } catch (IOException ignored) {
            // Stream closed because the process was killed; keep what we have.
        }
    }

    boolean limitExceeded() {
        return limitExceeded;
    }

    synchronized String text() {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
