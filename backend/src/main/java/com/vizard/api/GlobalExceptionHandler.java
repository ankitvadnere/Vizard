package com.vizard.api;

import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.ExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** Turns bad requests and unexpected failures into the same JSON shape as normal results. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExecutionResponse> invalid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ExecutionResponse.of(ExecutionStatus.INVALID_REQUEST, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ExecutionResponse> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ExecutionResponse.of(ExecutionStatus.INVALID_REQUEST, "Request body must be JSON: {\"code\": \"...\"}"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ExecutionResponse> unexpected(Exception ex) {
        log.error("Unexpected error while handling request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ExecutionResponse.of(ExecutionStatus.INTERNAL_ERROR, "Vizard hit an internal error: " + ex.getMessage()));
    }
}
