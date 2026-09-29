package com.vizard.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/execute.
 *
 * @param code  complete Java source (one file, one or more top-level classes)
 * @param stdin optional text fed to System.in (for Scanner-based programs)
 */
public record ExecuteRequest(
        @NotBlank(message = "Code must not be empty")
        @Size(max = 50_000, message = "Code must be at most 50,000 characters")
        String code,

        @Size(max = 10_000, message = "Input must be at most 10,000 characters")
        String stdin
) {
}
