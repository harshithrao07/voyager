package com.job.scheduler.controller;

import com.job.scheduler.dto.FunctionDefinitionResponseDTO;
import com.job.scheduler.dto.FunctionInvocationResponseDTO;
import com.job.scheduler.dto.FunctionRunResultDTO;
import com.job.scheduler.dto.FunctionVersionResponseDTO;
import com.job.scheduler.dto.Judge0RuntimeInfoDTO;
import com.job.scheduler.exception.ApiExceptionHandler;
import com.job.scheduler.service.FunctionInvocationService;
import com.job.scheduler.service.FunctionRegistryService;
import com.job.scheduler.service.FunctionRuntimePolicy;
import com.job.scheduler.service.Judge0RuntimeService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FunctionControllerTest {
    @Mock
    private FunctionRegistryService functionRegistryService;
    @Mock
    private FunctionInvocationService functionInvocationService;
    @Mock
    private FunctionRuntimePolicy functionRuntimePolicy;
    @Mock
    private Judge0RuntimeService judge0RuntimeService;

    private MockMvc mockMvc;
    private final UUID functionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FunctionController(
                        functionRegistryService,
                        functionInvocationService,
                        functionRuntimePolicy,
                        judge0RuntimeService
                ))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getLanguagesReturnsSupportedLanguages() throws Exception {
        when(functionRuntimePolicy.supportedSelectableLanguages()).thenReturn(List.of());

        mockMvc.perform(get("/app/v1/functions/languages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getRuntimeInfoReturnsSnapshot() throws Exception {
        when(judge0RuntimeService.runtimeInfo()).thenReturn(runtimeInfo());

        mockMvc.perform(get("/app/v1/functions/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.languageCount").value(2));
    }

    @Test
    void runExecutesAdHocFunction() throws Exception {
        when(functionInvocationService.run(any())).thenReturn(runResult());

        String body = """
                {
                  "languageId": 71,
                  "sourceCode": "print(1)"
                }
                """;

        mockMvc.perform(post("/app/v1/functions/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("1"));
    }

    @Test
    void runValidatesLanguageId() throws Exception {
        String body = """
                {
                  "sourceCode": "print(1)"
                }
                """;

        mockMvc.perform(post("/app/v1/functions/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void createFunctionReturnsDefinition() throws Exception {
        when(functionRegistryService.createFunction(any())).thenReturn(definition("calculate-tax"));

        String body = """
                {
                  "name": "calculate-tax",
                  "description": "Computes tax"
                }
                """;

        mockMvc.perform(post("/app/v1/functions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("calculate-tax"));
    }

    @Test
    void createFunctionRejectsInvalidName() throws Exception {
        String body = """
                {
                  "name": "Calculate Tax"
                }
                """;

        mockMvc.perform(post("/app/v1/functions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getFunctionsPassesIncludeArchivedFlag() throws Exception {
        when(functionRegistryService.getFunctions(true)).thenReturn(List.of());

        mockMvc.perform(get("/app/v1/functions").param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getFunctionReturnsDefinition() throws Exception {
        when(functionRegistryService.getFunction(functionId)).thenReturn(definition("calculate-tax"));

        mockMvc.perform(get("/app/v1/functions/{functionId}", functionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("calculate-tax"));
    }

    @Test
    void updateFunctionReturnsUpdatedDefinition() throws Exception {
        when(functionRegistryService.updateFunction(eq(functionId), any()))
                .thenReturn(definition("calculate-vat"));

        String body = """
                {
                  "name": "calculate-vat"
                }
                """;

        mockMvc.perform(put("/app/v1/functions/{functionId}", functionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("calculate-vat"));
    }

    @Test
    void deleteFunctionArchivesIt() throws Exception {
        when(functionRegistryService.archiveFunction(functionId)).thenReturn(definition("calculate-tax"));

        mockMvc.perform(delete("/app/v1/functions/{functionId}", functionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("calculate-tax"));
    }

    @Test
    void createVersionReturnsVersion() throws Exception {
        when(functionRegistryService.createVersion(eq(functionId), any())).thenReturn(version(1));

        String body = """
                {
                  "languageId": 71,
                  "sourceCode": "print(1)"
                }
                """;

        mockMvc.perform(post("/app/v1/functions/{functionId}/versions", functionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void getVersionsReturnsList() throws Exception {
        when(functionRegistryService.getVersions(functionId)).thenReturn(List.of());

        mockMvc.perform(get("/app/v1/functions/{functionId}/versions", functionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void updateVersionReturnsVersion() throws Exception {
        when(functionRegistryService.updateVersion(eq(functionId), eq(2), any())).thenReturn(version(2));

        String body = """
                {
                  "languageId": 71,
                  "sourceCode": "print(2)"
                }
                """;

        mockMvc.perform(put("/app/v1/functions/{functionId}/versions/{version}", functionId, 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void updateVersionMetadataReturnsVersion() throws Exception {
        when(functionRegistryService.updateVersionMetadata(eq(functionId), eq(2), any()))
                .thenReturn(version(2));

        String body = """
                {
                  "languageId": 71,
                  "note": "tweaked"
                }
                """;

        mockMvc.perform(put("/app/v1/functions/{functionId}/versions/{version}/metadata", functionId, 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void activateVersionReturnsDefinition() throws Exception {
        when(functionRegistryService.activateVersion(functionId, 3)).thenReturn(definition("calculate-tax"));

        mockMvc.perform(post("/app/v1/functions/{functionId}/versions/{version}/activate", functionId, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("calculate-tax"));
    }

    @Test
    void publishVersionReturnsVersion() throws Exception {
        when(functionRegistryService.publishVersion(functionId, 3)).thenReturn(version(3));

        mockMvc.perform(post("/app/v1/functions/{functionId}/versions/{version}/publish", functionId, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void updateVersionSettingsReturnsVersion() throws Exception {
        when(functionRegistryService.updateVersionSettings(eq(functionId), eq(2), any()))
                .thenReturn(version(2));

        String body = """
                {
                  "cpuTimeLimitSeconds": 2.0,
                  "wallTimeLimitSeconds": 5.0,
                  "memoryLimitKb": 131072,
                  "maxFileSizeKb": 1024,
                  "maxOutputBytes": 65536,
                  "enableNetwork": false
                }
                """;

        mockMvc.perform(put("/app/v1/functions/{functionId}/versions/{version}/settings", functionId, 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void testInvokeReturnsInvocation() throws Exception {
        when(functionInvocationService.testInvoke(eq(functionId), any())).thenReturn(invocation(1));

        String body = """
                {
                  "version": 1,
                  "input": {"a": 1}
                }
                """;

        mockMvc.perform(post("/app/v1/functions/{functionId}/test-invocations", functionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void getInvocationsReturnsList() throws Exception {
        when(functionInvocationService.getInvocations(functionId)).thenReturn(List.of());

        mockMvc.perform(get("/app/v1/functions/{functionId}/invocations", functionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private FunctionDefinitionResponseDTO definition(String name) {
        return new FunctionDefinitionResponseDTO(
                UUID.randomUUID(),
                name,
                "desc",
                1,
                null,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z")
        );
    }

    private FunctionVersionResponseDTO version(int version) {
        return new FunctionVersionResponseDTO(
                UUID.randomUUID(),
                functionId,
                version,
                null,
                71,
                true,
                false,
                "print(1)",
                null,
                List.of(),
                null,
                null,
                1.0,
                5.0,
                131072,
                1024,
                65536,
                false,
                null,
                List.of(),
                null,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z")
        );
    }

    private Judge0RuntimeInfoDTO runtimeInfo() {
        return new Judge0RuntimeInfoDTO(true, null, 2, 14, 4, 2, List.of(), null);
    }

    private FunctionRunResultDTO runResult() {
        return new FunctionRunResultDTO(
                null, null, "1", null, null, null, 0, null, null, null, null, null, null
        );
    }

    private FunctionInvocationResponseDTO invocation(int version) {
        return new FunctionInvocationResponseDTO(
                UUID.randomUUID(),
                functionId,
                version,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z")
        );
    }
}
