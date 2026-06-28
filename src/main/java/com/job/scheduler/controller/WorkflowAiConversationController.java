package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowAiAcceptPlanRequestDTO;
import com.job.scheduler.dto.WorkflowAiChatRequestDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowAiReviewAslRequestDTO;
import com.job.scheduler.dto.WorkflowAiStartRequestDTO;
import com.job.scheduler.service.WorkflowAiConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/app/v1/workflow-ai")
public class WorkflowAiConversationController {
    private final WorkflowAiConversationService workflowAiConversationService;

    @PostMapping("/conversations")
    public ResponseEntity<WorkflowAiResponseDTO> startConversation(
            @Valid @RequestBody WorkflowAiStartRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.startConversation(
                request.instruction(),
                request.modelConfigId(),
                request.userDateTime()
        ));
    }

    @PostMapping("/messages")
    public ResponseEntity<WorkflowAiResponseDTO> continueConversation(
            @Valid @RequestBody WorkflowAiChatRequestDTO request
    ) {
        return ResponseEntity.ok(workflowAiConversationService.continueConversation(
                request.conversationId(),
                request.message()
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

    @MessageMapping("/workflow-ai/start")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO startConversationSocket(
            @Valid @Payload WorkflowAiStartRequestDTO request
    ) {
        return workflowAiConversationService.startConversation(
                request.instruction(),
                request.modelConfigId(),
                request.userDateTime()
        );
    }

    @MessageMapping("/workflow-ai/message")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO continueConversationSocket(
            @Valid @Payload WorkflowAiChatRequestDTO request
    ) {
        return workflowAiConversationService.continueConversation(
                request.conversationId(),
                request.message()
        );
    }

    @MessageMapping("/workflow-ai/review-asl")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO reviewAslSocket(
            @Valid @Payload WorkflowAiReviewAslRequestDTO request
    ) {
        return workflowAiConversationService.reviewAsl(
                request.conversationId(),
                request.definition()
        );
    }

    @MessageMapping("/workflow-ai/accept")
    @SendToUser("/queue/workflow-ai")
    public WorkflowAiResponseDTO acceptPlanSocket(
            @Valid @Payload WorkflowAiAcceptPlanRequestDTO request
    ) {
        return workflowAiConversationService.acceptPlan(request.conversationId());
    }
}
