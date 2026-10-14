package com.vizard.api.dto.trace;

import java.util.List;

/**
 * What the current line does with a dynamic-programming table: the cell it computes and the cells
 * it reads, with the expression as written and with the values filled in, so the recurrence can be
 * seen: {@code dp[2][4] = max(dp[1][4], dp[1][1] + 20)} → {@code max(15, 15 + 20)} → 35.
 *
 * @param table   the table variable
 * @param ref     its heap id
 * @param cell    {row, column} of the cell being computed (row 0 for a 1-D table)
 * @param reads   cells of the table this line reads, as {row, column}
 * @param label   what the line computes: "dp[i][w]", a local such as "cost", or "check"
 * @param formula the expression as written
 * @param values  the same expression with the values it reads
 * @param result  the value it produced (the cell after the write, the local, true/false), or null
 * @param write   the line writes the cell
 */
public record DpInsight(String table, long ref, List<Integer> cell, List<List<Integer>> reads, String label,
                        String formula, String values, String result, boolean write) {
}
