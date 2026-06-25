package com.job.scheduler.controller;

import com.job.scheduler.dto.WorkflowGenerationRequestDTO;
import com.job.scheduler.dto.WorkflowGenerationResponseDTO;
import com.job.scheduler.service.WorkflowGenerationService;
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

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowAiController(workflowGenerationService)).build();
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
}
