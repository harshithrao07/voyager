package com.job.scheduler.controller;

import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.dto.AiModelEvaluationDTO;
import com.job.scheduler.dto.AiModelEvaluationHistoryDTO;
import com.job.scheduler.dto.AiModelTestResponseDTO;
import com.job.scheduler.enums.AiModelEvaluationMode;
import com.job.scheduler.enums.AiModelEvaluationStatus;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiModelRole;
import com.job.scheduler.enums.AiStructuredOutputMode;
import com.job.scheduler.exception.ApiExceptionHandler;
import com.job.scheduler.service.AiModelConfigService;
import com.job.scheduler.service.AiModelEvaluationService;
import com.job.scheduler.service.EmbeddingRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiModelControllerTest {
    @Mock
    private AiModelConfigService aiModelConfigService;
    @Mock
    private AiModelEvaluationService aiModelEvaluationService;
    @Mock
    private EmbeddingRankingService embeddingRankingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AiModelController(
                        aiModelConfigService,
                        aiModelEvaluationService,
                        embeddingRankingService
                ))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listModelsReturnsEnabledModels() throws Exception {
        when(aiModelConfigService.listEnabledModels()).thenReturn(List.of(model("GPT Local", true)));

        mockMvc.perform(get("/app/v1/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("GPT Local"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void listAllModelsReturnsEveryModel() throws Exception {
        when(aiModelConfigService.listAllModels())
                .thenReturn(List.of(model("Enabled", true), model("Disabled", false)));

        mockMvc.perform(get("/app/v1/ai/models/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void createModelReturnsCreatedModel() throws Exception {
        when(aiModelConfigService.createModel(any())).thenReturn(model("GPT Local", true));

        String body = """
                {
                  "displayName": "GPT Local",
                  "providerType": "OPENAI_COMPATIBLE_LOCAL",
                  "baseUrl": "http://localhost:11434/v1",
                  "modelName": "llama3",
                  "defaultModel": false
                }
                """;

        mockMvc.perform(post("/app/v1/ai/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("GPT Local"));
    }

    @Test
    void createModelValidatesBlankFields() throws Exception {
        String body = """
                {
                  "displayName": "",
                  "providerType": "OPENAI_COMPATIBLE_LOCAL",
                  "baseUrl": "",
                  "modelName": "",
                  "defaultModel": false
                }
                """;

        mockMvc.perform(post("/app/v1/ai/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3));
    }

    @Test
    void testLocalModelReturnsResult() throws Exception {
        when(aiModelConfigService.testLocalModel(any()))
                .thenReturn(new AiModelTestResponseDTO(true, "reachable"));

        String body = """
                {
                  "baseUrl": "http://localhost:11434/v1",
                  "modelName": "llama3"
                }
                """;

        mockMvc.perform(post("/app/v1/ai/models/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("reachable"));
    }

    @Test
    void discoverModelsForwardsRequestFields() throws Exception {
        when(aiModelConfigService.discoverAndOnboardModels(
                eq("http://localhost:11434/v1"),
                eq("cred-1"),
                eq(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL),
                eq(AiModelRole.EMBEDDING)))
                .thenReturn(List.of(model("Discovered", true)));

        String body = """
                {
                  "baseUrl": "http://localhost:11434/v1",
                  "credential": "cred-1",
                  "providerType": "OPENAI_COMPATIBLE_LOCAL",
                  "role": "EMBEDDING"
                }
                """;

        mockMvc.perform(post("/app/v1/ai/models/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Discovered"));
    }

    @Test
    void setModelEnabledViaPatch() throws Exception {
        UUID modelId = UUID.randomUUID();
        when(aiModelConfigService.setModelEnabled(modelId, false))
                .thenReturn(model("GPT Local", false));

        mockMvc.perform(patch("/app/v1/ai/models/{modelId}/enabled", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void setModelEnabledViaPost() throws Exception {
        UUID modelId = UUID.randomUUID();
        when(aiModelConfigService.setModelEnabled(modelId, true))
                .thenReturn(model("GPT Local", true));

        mockMvc.perform(post("/app/v1/ai/models/{modelId}/enabled", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void deleteModelReturnsNoContent() throws Exception {
        UUID modelId = UUID.randomUUID();

        mockMvc.perform(delete("/app/v1/ai/models/{modelId}", modelId))
                .andExpect(status().isNoContent());

        verify(aiModelConfigService).deleteModel(modelId);
    }

    @Test
    void listLatestEvaluationsReturnsStoredResults() throws Exception {
        AiModelEvaluationDTO evaluation = evaluation(AiModelEvaluationStatus.COMPLETED);
        when(aiModelEvaluationService.listLatest()).thenReturn(List.of(evaluation));

        mockMvc.perform(get("/app/v1/ai/models/evaluations/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(evaluation.runId().toString()))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].mode").value("QUICK"))
                .andExpect(jsonPath("$[0].stale").value(false));
    }

    @Test
    void startEvaluationReturnsAcceptedRun() throws Exception {
        UUID modelId = UUID.randomUUID();
        AiModelEvaluationDTO evaluation = evaluation(AiModelEvaluationStatus.RUNNING);
        when(aiModelEvaluationService.start(modelId, AiModelEvaluationMode.RELIABILITY, null))
                .thenReturn(evaluation);

        mockMvc.perform(post("/app/v1/ai/models/{modelId}/evaluations", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"RELIABILITY\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(aiModelEvaluationService).start(modelId, AiModelEvaluationMode.RELIABILITY, null);
    }

    @Test
    void listsPaginatedEvaluationHistoryForAModel() throws Exception {
        UUID modelId = UUID.randomUUID();
        AiModelEvaluationDTO evaluation = evaluation(AiModelEvaluationStatus.COMPLETED);
        when(aiModelEvaluationService.history(modelId, 1, 5)).thenReturn(
                new AiModelEvaluationHistoryDTO(List.of(evaluation), 1, 5, 8, 2)
        );

        mockMvc.perform(get("/app/v1/ai/models/{modelId}/evaluations", modelId)
                        .queryParam("page", "1")
                        .queryParam("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].runId").value(evaluation.runId().toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(8))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void startEvaluationPassesTheJudgeModelThrough() throws Exception {
        UUID modelId = UUID.randomUUID();
        UUID judgeModelId = UUID.randomUUID();
        AiModelEvaluationDTO evaluation = evaluation(AiModelEvaluationStatus.RUNNING);
        when(aiModelEvaluationService.start(modelId, AiModelEvaluationMode.QUICK, judgeModelId))
                .thenReturn(evaluation);

        mockMvc.perform(post("/app/v1/ai/models/{modelId}/evaluations", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"QUICK\",\"judgeModelConfigId\":\""
                                + judgeModelId + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(aiModelEvaluationService).start(modelId, AiModelEvaluationMode.QUICK, judgeModelId);
    }

    @Test
    void cancelEvaluationReturnsUpdatedRun() throws Exception {
        UUID modelId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AiModelEvaluationDTO evaluation = evaluation(AiModelEvaluationStatus.RUNNING);
        when(aiModelEvaluationService.cancel(modelId, runId)).thenReturn(evaluation);

        mockMvc.perform(post(
                        "/app/v1/ai/models/{modelId}/evaluations/{runId}/cancel",
                        modelId,
                        runId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(aiModelEvaluationService).cancel(modelId, runId);
    }

    private AiModelEvaluationDTO evaluation(AiModelEvaluationStatus status) {
        return new AiModelEvaluationDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "GPT Local",
                status,
                AiModelEvaluationMode.QUICK,
                1,
                status == AiModelEvaluationStatus.COMPLETED ? 7 : 0,
                7,
                false,
                false,
                null,
                null,
                null,
                Instant.parse("2026-07-26T00:00:00Z"),
                status == AiModelEvaluationStatus.COMPLETED
                        ? Instant.parse("2026-07-26T00:01:00Z")
                        : null
        );
    }

    private AiModelConfigDTO model(String displayName, boolean enabled) {
        return new AiModelConfigDTO(
                UUID.randomUUID(),
                displayName,
                AiModelProviderType.OPENAI_COMPATIBLE_LOCAL,
                AiModelRole.CHAT,
                "http://localhost:11434/v1",
                "llama3",
                enabled,
                false,
                false,
                AiStructuredOutputMode.UNKNOWN
        );
    }
}
