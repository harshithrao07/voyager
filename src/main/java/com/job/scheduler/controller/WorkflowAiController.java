package com.job.scheduler.controller;

import com.job.scheduler.dto.*;
import com.job.scheduler.service.WorkflowAiAuthoringService;
import com.job.scheduler.service.WorkflowGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "Workflow AI Generation", description = "AI-powered ASL workflow generation")
public class WorkflowAiController {

    private final WorkflowGenerationService workflowGenerationService;
    private final WorkflowAiAuthoringService workflowAiAuthoringService;

    @PostMapping("/generate")
    @Operation(summary = "Generate ASL Workflow from Natural Language", description = "Uses LLM to convert a natural language instruction into a JSONata-compatible ASL workflow definition.")
    public ResponseEntity<WorkflowGenerationResponseDTO> generateWorkflow(@Valid @RequestBody WorkflowGenerationRequestDTO request) {
        WorkflowGenerationResponseDTO response = workflowGenerationService.generateWorkflow(request.getInstruction());
        if (response.getDefinition() == null) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/authoring/explain")
    @Operation(summary = "Explain an ASL workflow")
    public WorkflowAiExplanationResponseDTO explain(
            @Valid @RequestBody WorkflowAiAuthoringRequestDTO request
    ) {
        return workflowAiAuthoringService.explain(request.definition(), request.modelConfigId());
    }

    @PostMapping("/authoring/pre-activation-review")
    @Operation(summary = "Review an ASL workflow for activation risks")
    public WorkflowPreActivationReviewResponseDTO reviewBeforeActivation(
            @Valid @RequestBody WorkflowAiAuthoringRequestDTO request
    ) {
        return workflowAiAuthoringService.reviewBeforeActivation(
                request.definition(), request.modelConfigId());
    }

}
