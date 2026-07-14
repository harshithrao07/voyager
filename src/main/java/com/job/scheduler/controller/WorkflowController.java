package com.job.scheduler.controller;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.CreateWorkflowRevisionRequestDTO;
import com.job.scheduler.dto.DraftStateTestRequestDTO;
import com.job.scheduler.dto.DraftStateTestResponseDTO;
import com.job.scheduler.dto.StartWorkflowExecutionRequestDTO;
import com.job.scheduler.dto.WorkflowDefinitionResponseDTO;
import com.job.scheduler.dto.WorkflowExecutionResponseDTO;
import com.job.scheduler.dto.WorkflowExecutionCancellationResponseDTO;
import com.job.scheduler.dto.WorkflowExecutionDetailDTO;
import com.job.scheduler.dto.WorkflowExecutionPageDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowPageDTO;
import com.job.scheduler.dto.UpdateWorkflowMetadataRequestDTO;
import com.job.scheduler.dto.UpdateWorkflowCanvasLayoutRequestDTO;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.service.WorkflowExecutionInspectionService;
import com.job.scheduler.service.WorkflowDraftTestService;
import com.job.scheduler.service.WorkflowExecutionCancellationService;
import com.job.scheduler.service.WorkflowExecutionRunner;
import com.job.scheduler.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/app/v1/workflows")
public class WorkflowController {
    private final WorkflowService workflowService;
    private final WorkflowExecutionRunner workflowExecutionRunner;
    private final WorkflowExecutionInspectionService
            workflowExecutionInspectionService;
    private final WorkflowExecutionCancellationService
            workflowExecutionCancellationService;
    private final WorkflowDraftTestService workflowDraftTestService;

    @PostMapping
    public ResponseEntity<WorkflowResponseDTO> createWorkflow(
            @Valid @RequestBody CreateWorkflowRequestDTO request
    ) {
        return ResponseEntity.ok(workflowService.createWorkflow(request));
    }

    @PostMapping("/draft-tests/state")
    public ResponseEntity<DraftStateTestResponseDTO> testDraftState(
            @Valid @RequestBody DraftStateTestRequestDTO request
    ) {
        return ResponseEntity.ok(workflowDraftTestService.testState(request));
    }

    @GetMapping
    public ResponseEntity<WorkflowPageDTO> listWorkflows(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam(required = false) String name
    ) {
        return ResponseEntity.ok(
                workflowService.listWorkflows(page, size, status, name)
        );
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<WorkflowResponseDTO> getWorkflow(
            @PathVariable UUID workflowId
    ) {
        return ResponseEntity.ok(workflowService.getWorkflow(workflowId));
    }

    @PatchMapping("/{workflowId}")
    public ResponseEntity<WorkflowResponseDTO> updateWorkflowMetadata(
            @PathVariable UUID workflowId,
            @Valid @RequestBody UpdateWorkflowMetadataRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowService.updateMetadata(workflowId, request)
        );
    }

    @PostMapping("/{workflowId}/revisions")
    public ResponseEntity<WorkflowDefinitionResponseDTO> createRevision(
            @PathVariable UUID workflowId,
            @Valid @RequestBody CreateWorkflowRevisionRequestDTO request
    ) {
        return ResponseEntity.ok(workflowService.createRevision(workflowId, request));
    }

    @GetMapping("/{workflowId}/revisions")
    public ResponseEntity<List<WorkflowDefinitionResponseDTO>> getRevisions(
            @PathVariable UUID workflowId
    ) {
        return ResponseEntity.ok(workflowService.getRevisions(workflowId));
    }

    @PutMapping("/{workflowId}/revisions/{revision}/canvas-layout")
    public ResponseEntity<WorkflowDefinitionResponseDTO> updateCanvasLayout(
            @PathVariable UUID workflowId,
            @PathVariable long revision,
            @Valid @RequestBody UpdateWorkflowCanvasLayoutRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowService.updateCanvasLayout(workflowId, revision, request)
        );
    }

    @PostMapping("/{workflowId}/revisions/{revision}/activate")
    public ResponseEntity<WorkflowDefinitionResponseDTO> activateRevision(
            @PathVariable UUID workflowId,
            @PathVariable long revision
    ) {
        return ResponseEntity.ok(workflowService.activateRevision(workflowId, revision));
    }

    @PostMapping("/{workflowId}/executions")
    public ResponseEntity<WorkflowExecutionResponseDTO> startExecution(
            @PathVariable UUID workflowId,
            @RequestBody(required = false)
            StartWorkflowExecutionRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowExecutionRunner.start(workflowId, request)
        );
    }

    @GetMapping("/{workflowId}/executions")
    public ResponseEntity<WorkflowExecutionPageDTO> listExecutions(
            @PathVariable UUID workflowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                workflowExecutionInspectionService.listExecutions(
                        workflowId,
                        page,
                        size
                )
        );
    }

    @GetMapping("/{workflowId}/executions/{executionId}")
    public ResponseEntity<WorkflowExecutionDetailDTO> getExecution(
            @PathVariable UUID workflowId,
            @PathVariable UUID executionId
    ) {
        return ResponseEntity.ok(
                workflowExecutionInspectionService.getExecution(
                        workflowId,
                        executionId
                )
        );
    }

    @PostMapping("/{workflowId}/pause")
    public ResponseEntity<WorkflowResponseDTO> pauseWorkflow(
            @PathVariable UUID workflowId
    ) {
        return ResponseEntity.ok(workflowService.pauseWorkflow(workflowId));
    }

    @PostMapping("/{workflowId}/resume")
    public ResponseEntity<WorkflowResponseDTO> resumeWorkflow(
            @PathVariable UUID workflowId
    ) {
        return ResponseEntity.ok(workflowService.resumeWorkflow(workflowId));
    }

    @PostMapping("/{workflowId}/archive")
    public ResponseEntity<WorkflowResponseDTO> archiveWorkflow(
            @PathVariable UUID workflowId
    ) {
        return ResponseEntity.ok(workflowService.archiveWorkflow(workflowId));
    }

    @PostMapping("/{workflowId}/executions/{executionId}/cancel")
    public ResponseEntity<WorkflowExecutionCancellationResponseDTO>
    cancelExecution(
            @PathVariable UUID workflowId,
            @PathVariable UUID executionId
    ) {
        return ResponseEntity.ok(
                workflowExecutionCancellationService.cancelExecution(
                        workflowId,
                        executionId
                )
        );
    }
}
