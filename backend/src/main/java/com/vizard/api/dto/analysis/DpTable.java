package com.vizard.api.dto.analysis;

import java.util.List;

/**
 * A dynamic-programming table and the order its cells were filled in this run.
 *
 * @param method    the method that fills it
 * @param table     its variable
 * @param kind      "tabulation" or "memo"
 * @param problem   "fibonacci", "knapsack", "lcs", "edit-distance", "coin-change", "matrix-chain" or "dp"
 * @param dims      1 or 2
 * @param rows      rows of the table (1 for a 1-D table)
 * @param cols      columns (the length of a 1-D table)
 * @param rowVar    what a row stands for (i), or null
 * @param colVar    what a column stands for (w, j; the index of a 1-D table), or null
 * @param rowLabels letters labelling the rows (LCS: "" then the letters of a), or null
 * @param colLabels letters labelling the columns, or null
 * @param writes    every write of a cell, in order, with the step at which its new value is visible
 */
public record DpTable(String method, String table, String kind, String problem, int dims, int rows, int cols,
                      String rowVar, String colVar,
                      List<String> rowLabels, List<String> colLabels, List<DpWrite> writes) {
}
