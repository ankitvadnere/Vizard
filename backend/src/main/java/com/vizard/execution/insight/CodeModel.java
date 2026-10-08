package com.vizard.execution.insight;

import java.util.List;
import java.util.Map;

/**
 * Line-level facts about the program, extracted once from the AST before tracing.
 *
 * @param conditions  conditions keyed by the line where they are evaluated
 * @param loops       every loop in the file
 * @param accesses    array accesses keyed by line
 * @param methods     methods and constructors with their line ranges and pointer variables
 */
public record CodeModel(
        Map<Integer, List<ConditionSite>> conditions,
        List<LoopSite> loops,
        Map<Integer, List<ArrayAccessSite>> accesses,
        List<MethodSite> methods
) {

    public static CodeModel empty() {
        return new CodeModel(Map.of(), List.of(), Map.of(), List.of());
    }

    /**
     * A condition. For loops the "branch" is the body; for if statements it is the then-part.
     *
     * @param kind        "if", "while", "for" or "do"
     * @param line        line where execution stops to evaluate it (the for header for for-loops)
     * @param branchStart first line of the code that runs when it is true
     * @param branchEnd   last line of that code
     */
    public record ConditionSite(String kind, int line, Expr expression, int branchStart, int branchEnd) {

        /** True if the branch starts on another line, so control flow alone reveals the result. */
        boolean branchOnOtherLines() {
            return line < branchStart || line > branchEnd;
        }

        boolean branchContains(int otherLine) {
            return otherLine >= branchStart && otherLine <= branchEnd;
        }
    }

    /**
     * @param kind          "for", "for-each", "while" or "do"
     * @param headerLine    line of the for/while keyword, or of "do"
     * @param conditionLine line where the condition is checked (differs from headerLine only for do-while)
     * @param bodyStart     first line of the body
     * @param bodyEnd       last line of the body
     * @param endLine       last line of the whole statement
     * @param variable      loop variable of for/for-each loops, else null
     */
    public record LoopSite(String kind, int headerLine, int conditionLine, int bodyStart, int bodyEnd,
                           int endLine, String variable) {

        boolean contains(int line) {
            return line >= headerLine && line <= endLine;
        }

        boolean bodyContains(int line) {
            return line >= bodyStart && line <= bodyEnd;
        }

        /** Loops written on one line can't be followed iteration by iteration through line changes. */
        boolean onOneLine() {
            return bodyStart == headerLine && bodyEnd == headerLine;
        }
    }

    /**
     * @param array       the array part, e.g. Name("arr")
     * @param index       the index part, e.g. j + 1
     * @param write       the element is assigned (arr[i] = ..., arr[i]++)
     * @param inCondition the access is part of an if/loop condition
     */
    public record ArrayAccessSite(int line, Expr array, Expr index, String text, boolean write, boolean inCondition) {
    }

    /** An array expression and the int variables used to index it inside one method. */
    public record PointerSpec(Expr array, List<String> variables) {
    }

    public record MethodSite(String name, int startLine, int endLine, List<PointerSpec> pointers) {

        boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }
    }
}
