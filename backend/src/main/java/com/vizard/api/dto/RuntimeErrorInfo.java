package com.vizard.api.dto;

/**
 * An uncaught exception thrown by the user program.
 *
 * @param exceptionType e.g. "java.lang.ArrayIndexOutOfBoundsException"
 * @param message       the exception message, may be null
 * @param line          line in the user's source where it was thrown, or 0 if unknown
 */
public record RuntimeErrorInfo(String exceptionType, String message, int line) {
}
