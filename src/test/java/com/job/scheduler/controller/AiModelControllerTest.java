package com.job.scheduler.controller;

import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.dto.AiModelTestResponseDTO;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.exception.ApiExceptionHandler;
import com.job.scheduler.service.AiModelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AiModelController(aiModelConfigService))
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
                eq(AiModelProviderType.OPENAI_COMPATIBLE_LOCAL)))
                .thenReturn(List.of(model("Discovered", true)));

        String body = """
                {
                  "baseUrl": "http://localhost:11434/v1",
                  "credential": "cred-1",
                  "providerType": "OPENAI_COMPATIBLE_LOCAL"
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

    private AiModelConfigDTO model(String displayName, boolean enabled) {
        return new AiModelConfigDTO(
                UUID.randomUUID(),
                displayName,
                AiModelProviderType.OPENAI_COMPATIBLE_LOCAL,
                "http://localhost:11434/v1",
                "llama3",
                enabled,
                false,
                false
        );
    }
}
