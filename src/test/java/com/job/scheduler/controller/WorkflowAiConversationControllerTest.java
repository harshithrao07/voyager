package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowAiChatRequestDTO;
import com.job.scheduler.dto.WorkflowAiConversationDetailDTO;
import com.job.scheduler.dto.WorkflowAiConversationSummaryDTO;
import com.job.scheduler.dto.WorkflowAiReviewAslRequestDTO;
import com.job.scheduler.dto.WorkflowAiStartRequestDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowAiAcceptPlanRequestDTO;
import com.job.scheduler.exception.ApiExceptionHandler;
import com.job.scheduler.service.WorkflowAiConversationService;
import com.job.scheduler.service.WorkflowAiStreamBroker;
import com.job.scheduler.service.WorkflowAiTurnRegistry;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkflowAiConversationControllerTest {
    @Mock
    private WorkflowAiConversationService service;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private WorkflowAiConversationController controller;
    private MockMvc mockMvc;
    private final UUID conversationId = UUID.randomUUID();
    private static final String SESSION_ID = "stomp-session-1";

    @BeforeEach
    void setUp() {
        // A real broker so withSession actually runs the turn; only the outbound template is mocked.
        controller = new WorkflowAiConversationController(
                service,
                new WorkflowAiStreamBroker(messagingTemplate, new WorkflowAiTurnRegistry())
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // --- REST endpoints ---

    @Test
    void listConversationsReturnsSummaries() throws Exception {
        when(service.listConversations()).thenReturn(List.of());

        mockMvc.perform(get("/app/v1/workflow-ai/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getConversationReturnsDetail() throws Exception {
        when(service.getConversation(conversationId)).thenReturn(detail());

        mockMvc.perform(get("/app/v1/workflow-ai/conversations/{id}", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Draft"));
    }

    @Test
    void manualDraftCrudUsesDedicatedRoutes() throws Exception {
        when(service.listDrafts()).thenReturn(List.of());
        when(service.createDraft(any())).thenReturn(detail());
        when(service.getDraft(conversationId)).thenReturn(detail());
        String body = """
                {
                  "definitionText": "{\\\"StartAt\\\":\\\"A\\\"}",
                  "canvasLayout": {},
                  "settings": {"name": "Manual draft", "maxAttempts": 3}
                }
                """;

        mockMvc.perform(get("/app/v1/workflow-ai/drafts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/app/v1/workflow-ai/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()));
        mockMvc.perform(get("/app/v1/workflow-ai/drafts/{id}", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Draft"));
        mockMvc.perform(put("/app/v1/workflow-ai/drafts/{id}/workspace", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(service).saveWorkspace(eq(conversationId), any());
    }

    @Test
    void chatAndDraftNamesUseDedicatedValidatedRoutes() throws Exception {
        when(service.renameConversation(conversationId, "Incident response"))
                .thenReturn(summary("Incident response"));
        when(service.renameDraft(conversationId, "Invoice draft"))
                .thenReturn(summary("Invoice draft"));

        mockMvc.perform(patch("/app/v1/workflow-ai/conversations/{id}/name", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Incident response\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Incident response"));

        mockMvc.perform(patch("/app/v1/workflow-ai/drafts/{id}/name", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Invoice draft\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Invoice draft"));

        mockMvc.perform(patch("/app/v1/workflow-ai/drafts/{id}/name", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void saveWorkspaceReturnsNoContent() throws Exception {
        String body = """
                {
                  "definition": {"StartAt": "A"},
                  "definitionText": "{\\\"StartAt\\\":\\\"A\\\"}",
                  "canvasLayout": {"nodes": []},
                  "settings": {"name": "wf", "maxAttempts": 3}
                }
                """;

        mockMvc.perform(put("/app/v1/workflow-ai/conversations/{id}/workspace", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(service).saveWorkspace(eq(conversationId), any());
    }

    @Test
    void saveWorkspaceValidatesBody() throws Exception {
        String body = """
                {
                  "canvasLayout": {"nodes": []},
                  "settings": {"name": "wf"}
                }
                """;

        mockMvc.perform(put("/app/v1/workflow-ai/conversations/{id}/workspace", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void saveConversationWorkflowUsesDedicatedRevisionAwareRoute() throws Exception {
        String body = """
                {
                  "workflow": {
                    "name": "Incident workflow",
                    "cronExpression": null,
                    "timezone": "UTC",
                    "maxAttempts": 3,
                    "idempotencyKey": "incident-workflow",
                    "definition": {
                      "StartAt": "Done",
                      "States": {"Done": {"Type": "Succeed"}}
                    }
                  },
                  "canvasLayout": {}
                }
                """;

        mockMvc.perform(post(
                        "/app/v1/workflow-ai/conversations/{id}/workflow",
                        conversationId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(service).saveConversationWorkflow(eq(conversationId), any());

        mockMvc.perform(post(
                        "/app/v1/workflow-ai/drafts/{id}/workflow",
                        conversationId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(service).saveDraftWorkflow(eq(conversationId), any());
    }

    @Test
    void startConversationReturnsResponse() throws Exception {
        when(service.startConversation(eq("Build a pipeline"), any(), any(), any(), any()))
                .thenReturn(response("Pipeline"));

        String body = """
                {
                  "instruction": "Build a pipeline",
                  "userDateTime": "2026-06-17T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/app/v1/workflow-ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationName").value("Pipeline"));
    }

    @Test
    void startConversationRejectsBlankInstruction() throws Exception {
        String body = """
                {
                  "instruction": ""
                }
                """;

        mockMvc.perform(post("/app/v1/workflow-ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void continueConversationReturnsResponse() throws Exception {
        when(service.continueConversation(eq(conversationId), eq("add a retry"), any(), any(), any()))
                .thenReturn(response("Pipeline"));

        String body = """
                {
                  "conversationId": "%s",
                  "message": "add a retry"
                }
                """.formatted(conversationId);

        mockMvc.perform(post("/app/v1/workflow-ai/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationName").value("Pipeline"));
    }

    @Test
    void regenerateMessageForwardsModelConfig() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID modelConfigId = UUID.randomUUID();
        when(service.regenerateMessage(messageId, modelConfigId)).thenReturn(response("Pipeline"));

        String body = """
                {
                  "modelConfigId": "%s"
                }
                """.formatted(modelConfigId);

        mockMvc.perform(post("/app/v1/workflow-ai/messages/{messageId}/regenerate", messageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationName").value("Pipeline"));
    }

    @Test
    void regenerateMessageHandlesNullBody() {
        UUID messageId = UUID.randomUUID();
        when(service.regenerateMessage(eq(messageId), isNull())).thenReturn(response("Pipeline"));

        // Spring's required @RequestBody rejects an empty body over HTTP, so the controller's
        // defensive null guard is only reachable by calling the method directly.
        WorkflowAiResponseDTO body = controller.regenerateMessage(messageId, null).getBody();

        assertThat(body).isNotNull();
        assertThat(body.conversationName()).isEqualTo("Pipeline");
    }

    @Test
    void reviewAslReturnsResponse() throws Exception {
        when(service.reviewAsl(eq(conversationId), any())).thenReturn(response("Pipeline"));

        String body = """
                {
                  "conversationId": "%s",
                  "definition": {"StartAt": "A"}
                }
                """.formatted(conversationId);

        mockMvc.perform(post("/app/v1/workflow-ai/review-asl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationName").value("Pipeline"));
    }

    @Test
    void acceptPlanReturnsResponse() throws Exception {
        when(service.acceptPlan(conversationId)).thenReturn(response("Pipeline"));

        String body = """
                {
                  "conversationId": "%s"
                }
                """.formatted(conversationId);

        mockMvc.perform(post("/app/v1/workflow-ai/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationName").value("Pipeline"));
    }

    // --- WebSocket (@MessageMapping) handlers: invoked as plain methods ---

    @Test
    void startConversationSocketDelegatesToService() {
        JsonNode definition = json("{\"StartAt\":\"A\"}");
        UUID modelConfigId = UUID.randomUUID();
        WorkflowAiStartRequestDTO request =
                new WorkflowAiStartRequestDTO("Build", modelConfigId, "2026-06-17T00:00:00Z", definition, null);
        when(service.startConversation("Build", modelConfigId, "2026-06-17T00:00:00Z", definition, null))
                .thenReturn(response("Pipeline"));

        WorkflowAiResponseDTO result = controller.startConversationSocket(request, SESSION_ID);

        assertThat(result.conversationName()).isEqualTo("Pipeline");
    }

    @Test
    void continueConversationSocketDelegatesToService() {
        WorkflowAiChatRequestDTO request =
                new WorkflowAiChatRequestDTO(conversationId, "next", null, null, null);
        when(service.continueConversation(conversationId, "next", null, null, null))
                .thenReturn(response("Pipeline"));

        assertThat(controller.continueConversationSocket(request, SESSION_ID).conversationName())
                .isEqualTo("Pipeline");
    }

    @Test
    void reviewAslSocketDelegatesToService() {
        JsonNode definition = json("{\"StartAt\":\"A\"}");
        WorkflowAiReviewAslRequestDTO request =
                new WorkflowAiReviewAslRequestDTO(conversationId, definition);
        when(service.reviewAsl(conversationId, definition)).thenReturn(response("Pipeline"));

        assertThat(controller.reviewAslSocket(request, SESSION_ID).conversationName()).isEqualTo("Pipeline");
    }

    @Test
    void acceptPlanSocketDelegatesToService() {
        WorkflowAiAcceptPlanRequestDTO request = new WorkflowAiAcceptPlanRequestDTO(conversationId);
        when(service.acceptPlan(conversationId)).thenReturn(response("Pipeline"));

        assertThat(controller.acceptPlanSocket(request, SESSION_ID).conversationName()).isEqualTo("Pipeline");
    }

    // --- helpers ---

    private WorkflowAiResponseDTO response(String conversationName) {
        return new WorkflowAiResponseDTO(
                conversationId, conversationName, null, "ok",
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private WorkflowAiConversationDetailDTO detail() {
        return new WorkflowAiConversationDetailDTO(
                conversationId, "Draft", null, null, null, "build",
                null, null, null, null, null, null, null, null, null, List.of(),
                null, null
        );
    }

    private WorkflowAiConversationSummaryDTO summary(String name) {
        return new WorkflowAiConversationSummaryDTO(
                conversationId, name, null, null, null, "build", null, null
        );
    }

    private JsonNode json(String raw) {
        return new ObjectMapper().readTree(raw);
    }
}
