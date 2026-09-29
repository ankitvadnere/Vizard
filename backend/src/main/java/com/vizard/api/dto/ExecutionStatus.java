package com.vizard.api.dto;

/** Every way a run can end. The frontend switches on this, never on message text. */
public enum ExecutionStatus {
    SUCCESS,
    COMPILATION_ERROR,
    UNSUPPORTED_FEATURE,
    RUNTIME_ERROR,
    TIMEOUT,
    MEMORY_LIMIT_EXCEEDED,
    OUTPUT_LIMIT_EXCEEDED,
    INVALID_REQUEST,
    SERVER_BUSY,
    INTERNAL_ERROR
}
