package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowGenerationRequestDTO;
import com.job.scheduler.dto.WorkflowGenerationResponseDTO;
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

    @PostMapping("/generate")
    @Operation(summary = "Generate ASL Workflow from Natural Language", description = "Uses LLM to convert a natural language instruction into a JSONata-compatible ASL workflow definition.")
    public ResponseEntity<WorkflowGenerationResponseDTO> generateWorkflow(@Valid @RequestBody WorkflowGenerationRequestDTO request) {
        WorkflowGenerationResponseDTO response = workflowGenerationService.generateWorkflow(request.getInstruction());
        if (response.getDefinition() == null) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }
}
