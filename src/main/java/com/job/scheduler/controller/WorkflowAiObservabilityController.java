package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowAiObservabilityDTO;
import com.job.scheduler.service.WorkflowAiObservabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Native LLM-observability metrics for the in-app panel, aggregated from persisted turn telemetry.
 * Sits alongside the Langfuse link-out — this is the quick summary, Langfuse is the deep dive.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/v1/ai/observability")
public class WorkflowAiObservabilityController {
    private final WorkflowAiObservabilityService observabilityService;

    @GetMapping("/metrics")
    public ResponseEntity<WorkflowAiObservabilityDTO> metrics(
            @RequestParam(name = "days", defaultValue = "30") int days
    ) {
        return ResponseEntity.ok(observabilityService.metrics(days));
    }
}
