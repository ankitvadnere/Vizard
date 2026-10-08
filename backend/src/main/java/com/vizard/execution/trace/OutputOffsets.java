package com.vizard.execution.trace;

import com.vizard.api.dto.trace.TraceStep;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher counts output in UTF-8 bytes; the browser slices a string by characters.
 * This converts each step's byte count to a character count ("é" is 2 bytes, 1 char).
 */
public final class OutputOffsets {

    private OutputOffsets() {
    }

    public static List<TraceStep> toCharacterOffsets(List<TraceStep> steps, String stdout) {
        // bytesBefore[i] = UTF-8 bytes used by stdout.substring(0, i), at code point boundaries.
        int n = stdout.length();
        long[] bytesBefore = new long[n + 1];
        int i = 0;
        while (i < n) {
            int cp = stdout.codePointAt(i);
            int width = Character.charCount(cp);
            long next = bytesBefore[i] + utf8Length(cp);
            for (int k = 1; k <= width; k++) {
                bytesBefore[i + k] = next; // a surrogate pair's first half is never a cut point
            }
            i += width;
        }

        List<TraceStep> converted = new ArrayList<>(steps.size());
        for (TraceStep s : steps) {
            int chars = charsForBytes(bytesBefore, s.outputLength());
            converted.add(s.withOutputLength(chars));
        }
        return converted;
    }

    /** Largest character count whose UTF-8 size is at most {@code bytes}. */
    private static int charsForBytes(long[] bytesBefore, long bytes) {
        int lo = 0;
        int hi = bytesBefore.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (bytesBefore[mid] <= bytes) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static int utf8Length(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }
}
