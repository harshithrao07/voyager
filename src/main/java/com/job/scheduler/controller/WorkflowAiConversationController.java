package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowAiConversationDetailDTO;
import com.job.scheduler.dto.WorkflowAiConversationSummaryDTO;
import com.job.scheduler.dto.WorkflowAiAcceptPlanRequestDTO;
import com.job.scheduler.dto.WorkflowAiChatRequestDTO;
import com.job.scheduler.dto.WorkflowAiProvisionRequestDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowAiRegenerateRequestDTO;
import com.job.scheduler.dto.WorkflowAiRenameRequestDTO;
import com.job.scheduler.dto.WorkflowAiReviewAslRequestDTO;
import com.job.scheduler.dto.WorkflowAiSaveWorkflowRequestDTO;
import com.job.scheduler.dto.WorkflowAiSaveWorkflowResponseDTO;
import com.job.scheduler.dto.WorkflowAiStartRequestDTO;
import com.job.scheduler.dto.WorkflowAiWorkspaceRequestDTO;
import com.job.scheduler.service.WorkflowAiConversationService;
import com.job.scheduler.service.WorkflowAiStreamBroker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/app/v1/workflow-ai")
public class WorkflowAiConversationController {
    private final WorkflowAiConversationService workflowAiConversationService;
    private final WorkflowAiStreamBroker streamBroker;

    @GetMapping("/conversations")
    public ResponseEntity<List<WorkflowAiConversationSummaryDTO>> listConversations() {
        return ResponseEntity.ok(workflowAiConversationService.listConversations());
    }

    @GetMapping("/drafts")
    public ResponseEntity<List<WorkflowAiConversationSummaryDTO>> listDrafts() {
        return ResponseEntity.ok(workflowAiConversationService.listDrafts());
    }

    @PostMapping("/drafts")
    public ResponseEntity<WorkflowAiConversationDetailDTO> createDraft(
            @Valid @RequestBody WorkflowAiWorkspaceRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.createDraft(request));
    }

    @GetMapping("/drafts/{draftId}")
    public ResponseEntity<WorkflowAiConversationDetailDTO> getDraft(
            @PathVariable UUID draftId
    ) {
        return ResponseEntity.ok(workflowAiConversationService.getDraft(draftId));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<WorkflowAiConversationDetailDTO> getConversation(
            @PathVariable UUID conversationId
    ) {
        return ResponseEntity.ok(
                workflowAiConversationService.getConversation(conversationId)
        );
    }

    @DeleteMapping("/conversations")
    public ResponseEntity<Void> deleteAllConversations() {
        workflowAiConversationService.deleteAllConversations();
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/conversations/{conversationId}/name")
    public ResponseEntity<WorkflowAiConversationSummaryDTO> renameConversation(
            @PathVariable UUID conversationId,
            @Valid @RequestBody WorkflowAiRenameRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowAiConversationService.renameConversation(conversationId, request.name())
        );
    }

    @PatchMapping("/drafts/{draftId}/name")
    public ResponseEntity<WorkflowAiConversationSummaryDTO> renameDraft(
            @PathVariable UUID draftId,
            @Valid @RequestBody WorkflowAiRenameRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowAiConversationService.renameDraft(draftId, request.name())
        );
    }

    @DeleteMapping("/drafts")
    public ResponseEntity<Void> deleteAllDrafts() {
        workflowAiConversationService.deleteAllDrafts();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/drafts/{draftId}")
    public ResponseEntity<Void> deleteDraft(@PathVariable UUID draftId) {
        workflowAiConversationService.deleteDraft(draftId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable UUID conversationId
    ) {
        workflowAiConversationService.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/conversations/{conversationId}/workspace")
    public ResponseEntity<Void> saveWorkspace(
            @PathVariable UUID conversationId,
            @Valid @RequestBody WorkflowAiWorkspaceRequestDTO request
    ) {
        workflowAiConversationService.saveWorkspace(conversationId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{conversationId}/workflow")
    public ResponseEntity<WorkflowAiSaveWorkflowResponseDTO> saveConversationWorkflow(
            @PathVariable UUID conversationId,
            @Valid @RequestBody WorkflowAiSaveWorkflowRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowAiConversationService.saveConversationWorkflow(conversationId, request)
        );
    }

    @PostMapping("/drafts/{draftId}/workflow")
    public ResponseEntity<WorkflowAiSaveWorkflowResponseDTO> saveDraftWorkflow(
            @PathVariable UUID draftId,
            @Valid @RequestBody WorkflowAiSaveWorkflowRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowAiConversationService.saveDraftWorkflow(draftId, request)
        );
    }

    @PutMapping("/drafts/{draftId}/workspace")
    public ResponseEntity<Void> saveDraftWorkspace(
            @PathVariable UUID draftId,
            @Valid @RequestBody WorkflowAiWorkspaceRequestDTO request
    ) {
        workflowAiConversationService.saveWorkspace(draftId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations")
    public ResponseEntity<WorkflowAiResponseDTO> startConversation(
            @Valid @RequestBody WorkflowAiStartRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.startConversation(
                request.instruction(),
                request.modelConfigId(),
                request.userDateTime(),
                request.definition(),
                request.definitionText()
        ));
    }

    @PostMapping("/messages")
    public ResponseEntity<WorkflowAiResponseDTO> continueConversation(
            @Valid @RequestBody WorkflowAiChatRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.continueConversation(
                request.conversationId(),
                request.message(),
                request.modelConfigId(),
                request.definition(),
                request.definitionText()
        ));
    }

    @PostMapping("/messages/{messageId}/regenerate")
    public ResponseEntity<WorkflowAiResponseDTO> regenerateMessage(
            @PathVariable UUID messageId,
            @RequestBody WorkflowAiRegenerateRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.regenerateMessage(
                messageId,
                request == null ? null : request.modelConfigId()
        ));
    }

    @PostMapping("/review-asl")
    public ResponseEntity<WorkflowAiResponseDTO> reviewAsl(
            @Valid @RequestBody WorkflowAiReviewAslRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.reviewAsl(
                request.conversationId(),
                request.definition()
        ));
    }

    @PostMapping("/accept")
    public ResponseEntity<WorkflowAiResponseDTO> acceptPlan(
            @Valid @RequestBody WorkflowAiAcceptPlanRequestDTO request
    ) {
        return ResponseEntity.ok(
                workflowAiConversationService.acceptPlan(request.conversationId())
        );
    }

    @PostMapping("/provision-resources")
    public ResponseEntity<WorkflowAiResponseDTO> provisionResources(
            @Valid @RequestBody WorkflowAiProvisionRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.provisionResources(
                request.conversationId(),
                request.functions(),
                request.modelConfigId()
        ));
    }

    // The socket mappings bind their STOMP session so the turn can push reasoning and stage frames
    // to this subscriber while it runs. The REST twins above stay non-streaming.
    @MessageMapping("/workflow-ai/start")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO startConversationSocket(
            @Valid @Payload WorkflowAiStartRequestDTO request,
            @Header("simpSessionId") String sessionId
    ) {
        return streamBroker.withSession(sessionId, () ->
                workflowAiConversationService.startConversation(
                        request.instruction(),
                        request.modelConfigId(),
                        request.userDateTime(),
                        request.definition(),
                        request.definitionText()
                ));
    }

    @MessageMapping("/workflow-ai/message")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO continueConversationSocket(
            @Valid @Payload WorkflowAiChatRequestDTO request,
            @Header("simpSessionId") String sessionId
    ) {
        return streamBroker.withSession(sessionId, () ->
                workflowAiConversationService.continueConversation(
                        request.conversationId(),
                        request.message(),
                        request.modelConfigId(),
                        request.definition(),
                        request.definitionText()
        ));
    }

    @MessageMapping("/workflow-ai/messages/{messageId}/regenerate")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO regenerateMessageSocket(
            @DestinationVariable UUID messageId,
            @Payload WorkflowAiRegenerateRequestDTO request,
            @Header("simpSessionId") String sessionId
    ) {
        return streamBroker.withSession(sessionId, () ->
                workflowAiConversationService.regenerateMessage(
                        messageId,
                        request == null ? null : request.modelConfigId()
                ));
    }

    @MessageMapping("/workflow-ai/review-asl")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO reviewAslSocket(
            @Valid @Payload WorkflowAiReviewAslRequestDTO request,
            @Header("simpSessionId") String sessionId
    ) {
        return streamBroker.withSession(sessionId, () ->
                workflowAiConversationService.reviewAsl(
                        request.conversationId(),
                        request.definition()
                ));
    }

    @MessageMapping("/workflow-ai/accept")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO acceptPlanSocket(
            @Valid @Payload WorkflowAiAcceptPlanRequestDTO request,
            @Header("simpSessionId") String sessionId
    ) {
        return streamBroker.withSession(sessionId, () ->
                workflowAiConversationService.acceptPlan(request.conversationId()));
    }
}
