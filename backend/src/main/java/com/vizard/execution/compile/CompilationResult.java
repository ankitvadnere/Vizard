package com.vizard.execution.compile;

import com.vizard.api.dto.SourceProblem;

import java.util.List;

public record CompilationResult(boolean success, List<SourceProblem> problems, long durationMs) {
}
