package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowGenerationRequestDTO;
import com.job.scheduler.dto.WorkflowGenerationResponseDTO;
import com.job.scheduler.dto.WorkflowPreActivationReviewResponseDTO;
import com.job.scheduler.dto.WorkflowPreActivationWarningDTO;
import com.job.scheduler.service.WorkflowGenerationService;
import com.job.scheduler.service.WorkflowAiAuthoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkflowAiControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkflowGenerationService workflowGenerationService;
    @Mock
    private WorkflowAiAuthoringService workflowAiAuthoringService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WorkflowAiController(workflowGenerationService, workflowAiAuthoringService)).build();
    }

    @Test
    void returnsGeneratedWorkflowSuccessfully() throws Exception {
        WorkflowGenerationRequestDTO request = new WorkflowGenerationRequestDTO();
        request.setInstruction("do something");

        WorkflowGenerationResponseDTO response = new WorkflowGenerationResponseDTO();
        response.setDefinition(objectMapper.readTree("{\"Type\": \"Pass\", \"End\": true}"));
        response.setRawOutput("{\"Type\": \"Pass\", \"End\": true}");
        response.setValidationIssues(List.of());

        when(workflowGenerationService.generateWorkflow("do something")).thenReturn(response);

        mockMvc.perform(post("/app/v1/workflows/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.Type").value("Pass"))
                .andExpect(jsonPath("$.validationIssues").isEmpty());
    }

    @Test
    void returnsBadRequestWhenValidationFailsAndMaxAttemptsExceeded() throws Exception {
        WorkflowGenerationRequestDTO request = new WorkflowGenerationRequestDTO();
        request.setInstruction("bad");

        WorkflowGenerationResponseDTO response = new WorkflowGenerationResponseDTO();
        response.setDefinition(null);
        response.setRawOutput("{\"Type\": \"Invalid\"}");
        response.setValidationIssues(List.of("INVALID_STATE_TYPE"));

        when(workflowGenerationService.generateWorkflow("bad")).thenReturn(response);

        mockMvc.perform(post("/app/v1/workflows/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationIssues[0]").value("INVALID_STATE_TYPE"));
    }

    @Test
    void reviewsWorkflowBeforeActivation() throws Exception {
        var definition = objectMapper.readTree("""
                {"StartAt":"Call","States":{"Call":{"Type":"Task",
                "Resource":"voyager://mcp/db/delete?trust=DESTRUCTIVE","End":true}}}
                """);
        when(workflowAiAuthoringService.reviewBeforeActivation(definition, null))
                .thenReturn(new WorkflowPreActivationReviewResponseDTO(List.of(
                        new WorkflowPreActivationWarningDTO(
                                "DESTRUCTIVE_MCP",
                                "Destructive operation",
                                "The task can permanently delete connected data.",
                                "Call")
                )));

        mockMvc.perform(post("/app/v1/workflows/authoring/pre-activation-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                objectMapper.createObjectNode().set("definition", definition))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0].category").value("DESTRUCTIVE_MCP"))
                .andExpect(jsonPath("$.warnings[0].stateName").value("Call"))
                .andExpect(jsonPath("$.warnings[0].solution").doesNotExist());
    }
}
