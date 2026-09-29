package com.vizard.api;

import com.vizard.api.dto.ExecuteRequest;
import com.vizard.api.dto.ExecutionResponse;
import com.vizard.api.dto.trace.TraceResponse;
import com.vizard.execution.ExecutionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /** Compile and run a program, returning its output. */
    @PostMapping("/execute")
    public ExecutionResponse execute(@Valid @RequestBody ExecuteRequest request) {
        return executionService.execute(request.code(), request.stdin());
    }

    /** Compile and run a program under the debugger, returning every execution step. */
    @PostMapping("/trace")
    public TraceResponse trace(@Valid @RequestBody ExecuteRequest request) {
        return executionService.trace(request.code(), request.stdin());
    }

    /** Lets the frontend check the backend is up. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "javaVersion", System.getProperty("java.version"));
    }
}
