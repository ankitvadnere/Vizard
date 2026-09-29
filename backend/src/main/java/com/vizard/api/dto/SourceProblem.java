package com.vizard.api.dto;

/**
 * A problem tied to a location in the user's source: a compiler error,
 * a parser error, or a feature Vizard does not allow.
 *
 * @param line     1-based line, or 0 if unknown
 * @param column   1-based column, or 0 if unknown
 * @param severity "ERROR" or "WARNING"
 */
public record SourceProblem(int line, int column, String message, String severity) {

    public static SourceProblem error(int line, int column, String message) {
        return new SourceProblem(line, column, message, "ERROR");
    }
}
