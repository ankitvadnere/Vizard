package com.vizard.execution.trace;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds loops written entirely on one line, e.g. {@code for (int i = 0; i < n; i++) sum += i;}.
 *
 * <p>A line breakpoint only fires at the <i>start</i> of a line's code, but such a loop jumps
 * back into the middle of its own line every iteration. This scans the method's bytecode for
 * backward jumps whose source and target lie on the same line, with no other line in between,
 * and returns the jump targets so an extra breakpoint can be placed there.
 */
final class SameLineLoops {

    private SameLineLoops() {
    }

    static List<Long> jumpTargets(Method method) throws AbsentInformationException {
        byte[] code = method.bytecodes();
        List<Location> lines = method.allLineLocations(); // ordered by code index
        if (code == null || code.length == 0 || lines.isEmpty()) {
            return List.of();
        }
        Set<Long> lineStarts = new HashSet<>();
        for (Location l : lines) {
            lineStarts.add(l.codeIndex());
        }

        List<Long> targets = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xff;
            long target = branchTarget(code, pc, op);
            if (target >= 0 && target < pc && !lineStarts.contains(target) && !targets.contains(target)) {
                int line = lineAt(lines, pc);
                if (line > 0 && lineAt(lines, target) == line && onlyLineBetween(lines, target, pc, line)) {
                    targets.add(target);
                }
            }
            int length = instructionLength(code, pc, op);
            if (length <= 0) {
                break; // unknown opcode: stop rather than misread the rest
            }
            pc += length;
        }
        return targets;
    }

    private static int lineAt(List<Location> lines, long codeIndex) {
        int line = -1;
        for (Location l : lines) {
            if (l.codeIndex() > codeIndex) {
                break;
            }
            line = l.lineNumber();
        }
        return line;
    }

    private static boolean onlyLineBetween(List<Location> lines, long from, long to, int line) {
        for (Location l : lines) {
            if (l.codeIndex() > from && l.codeIndex() <= to && l.lineNumber() != line) {
                return false;
            }
        }
        return true;
    }

    /** Absolute target of a jump instruction at pc, or -1 if it isn't one. */
    private static long branchTarget(byte[] code, int pc, int op) {
        if ((op >= 0x99 && op <= 0xa8) || op == 0xc6 || op == 0xc7) { // if*, goto, jsr, ifnull, ifnonnull
            return pc + (short) (((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff));
        }
        if (op == 0xc8 || op == 0xc9) { // goto_w, jsr_w
            return pc + readInt(code, pc + 1);
        }
        return -1;
    }

    private static int instructionLength(byte[] code, int pc, int op) {
        switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc:
                return 2;
            case 0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1,
                 0xc6, 0xc7:
                return 3;
            case 0xc5:
                return 4;
            case 0xb9, 0xba, 0xc8, 0xc9:
                return 5;
            case 0xc4: // wide
                return (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
            case 0xaa: { // tableswitch
                int base = (pc + 4) & ~3;
                int low = readInt(code, base + 4);
                int high = readInt(code, base + 8);
                return base - pc + 12 + 4 * (high - low + 1);
            }
            case 0xab: { // lookupswitch
                int base = (pc + 4) & ~3;
                int pairs = readInt(code, base + 4);
                return base - pc + 8 + 8 * pairs;
            }
            default:
                if (op >= 0x99 && op <= 0xa8) {
                    return 3; // conditional jumps, goto, jsr
                }
                return op <= 0xca ? 1 : -1;
        }
    }

    private static int readInt(byte[] code, int at) {
        return ((code[at] & 0xff) << 24) | ((code[at + 1] & 0xff) << 16)
                | ((code[at + 2] & 0xff) << 8) | (code[at + 3] & 0xff);
    }
}
