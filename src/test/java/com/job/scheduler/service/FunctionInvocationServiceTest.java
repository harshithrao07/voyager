package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionInvocationResponseDTO;
import com.job.scheduler.dto.FunctionRunRequestDTO;
import com.job.scheduler.dto.FunctionRunResultDTO;
import com.job.scheduler.dto.FunctionTestInvocationRequestDTO;
import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionInvocation;
import com.job.scheduler.entity.FunctionVersion;
import com.job.scheduler.enums.FunctionInvocationStatus;
import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionStatus;
import com.job.scheduler.enums.FunctionVersionStatus;
import com.job.scheduler.repository.FunctionInvocationRepository;
import com.job.scheduler.workflow.task.TaskResourceErrors;
import com.job.scheduler.workflow.task.TaskResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionInvocationServiceTest {
    @Mock
    private FunctionRegistryService functionRegistryService;

    @Mock
    private FunctionInvocationRepository invocationRepository;

    @Mock
    private Judge0Client judge0Client;

    @Mock
    private Judge0MultiFileSupport multiFileSupport;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FunctionInvocationService service;
    private FunctionDefinition function;
    private FunctionVersion version;

    @BeforeEach
    void setUp() {
        service = new FunctionInvocationService(
                functionRegistryService,
                invocationRepository,
                judge0Client,
                multiFileSupport,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(service, "maxPollDurationMs", 1000L);
        lenient().when(invocationRepository.save(any())).thenAnswer(invocation -> {
            FunctionInvocation saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            if (saved.getCreatedAt() == null) {
                saved.setCreatedAt(Instant.now());
            }
            if (saved.getUpdatedAt() == null) {
                saved.setUpdatedAt(Instant.now());
            }
            return saved;
        });

        function = function();
        version = version(function);
    }

    @Test
    void testInvokeReturnsParsedJsonOutputOnAcceptedSubmission()
            throws Exception {
        when(functionRegistryService.findFunction(function.getId()))
                .thenReturn(function);
        when(functionRegistryService.activeVersion(function)).thenReturn(version);
        when(judge0Client.createSubmission(any())).thenReturn("token-1");
        when(judge0Client.getSubmission("token-1")).thenReturn(new Judge0SubmissionResult(
                "token-1",
                3,
                "Accepted",
                "{\"ok\":true}",
                "",
                null,
                null,
                0,
                null,
                0.01,
                0.02,
                12000L
        ));

        FunctionInvocationResponseDTO response = service.testInvoke(
                function.getId(),
                new FunctionTestInvocationRequestDTO(
                        null,
                        objectMapper.readTree("{\"amount\":100}")
                )
        );

        assertThat(response.status()).isEqualTo(FunctionInvocationStatus.SUCCEEDED);
        assertThat(response.output().get("ok").booleanValue()).isTrue();
        assertThat(response.judge0Token()).isEqualTo("token-1");
    }

    @Test
    void testInvokeMapsCompilationError() {
        when(functionRegistryService.findFunction(function.getId()))
                .thenReturn(function);
        when(functionRegistryService.activeVersion(function)).thenReturn(version);
        when(judge0Client.createSubmission(any())).thenReturn("token-2");
        when(judge0Client.getSubmission("token-2")).thenReturn(new Judge0SubmissionResult(
                "token-2",
                6,
                "Compilation Error",
                null,
                null,
                "syntax error",
                null,
                null,
                null,
                null,
                null,
                null
        ));

        FunctionInvocationResponseDTO response = service.testInvoke(
                function.getId(),
                new FunctionTestInvocationRequestDTO(null, objectMapper.createObjectNode())
        );

        assertThat(response.status()).isEqualTo(FunctionInvocationStatus.FAILED);
        assertThat(response.errorName())
                .isEqualTo(TaskResourceErrors.FUNCTION_COMPILE_ERROR);
        assertThat(response.compileOutput()).isEqualTo("syntax error");
    }

    @Test
    void testInvokeMapsInvalidStdoutJson() {
        when(functionRegistryService.findFunction(function.getId()))
                .thenReturn(function);
        when(functionRegistryService.activeVersion(function)).thenReturn(version);
        when(judge0Client.createSubmission(any())).thenReturn("token-3");
        when(judge0Client.getSubmission("token-3")).thenReturn(new Judge0SubmissionResult(
                "token-3",
                3,
                "Accepted",
                "not json",
                "",
                null,
                null,
                0,
                null,
                null,
                null,
                null
        ));

        FunctionInvocationResponseDTO response = service.testInvoke(
                function.getId(),
                new FunctionTestInvocationRequestDTO(null, objectMapper.createObjectNode())
        );

        assertThat(response.status()).isEqualTo(FunctionInvocationStatus.FAILED);
        assertThat(response.errorName())
                .isEqualTo(TaskResourceErrors.FUNCTION_INVALID_OUTPUT);
    }

    @Test
    void testInvokeMapsRuntimeError() {
        when(functionRegistryService.findFunction(function.getId()))
                .thenReturn(function);
        when(functionRegistryService.activeVersion(function)).thenReturn(version);
        when(judge0Client.createSubmission(any())).thenReturn("token-5");
        when(judge0Client.getSubmission("token-5")).thenReturn(new Judge0SubmissionResult(
                "token-5",
                11,
                "Runtime Error (NZEC)",
                null,
                "boom",
                null,
                null,
                1,
                null,
                null,
                null,
                null
        ));

        FunctionInvocationResponseDTO response = service.testInvoke(
                function.getId(),
                new FunctionTestInvocationRequestDTO(null, objectMapper.createObjectNode())
        );

        assertThat(response.status()).isEqualTo(FunctionInvocationStatus.FAILED);
        assertThat(response.errorName())
                .isEqualTo(TaskResourceErrors.FUNCTION_RUNTIME_ERROR);
        assertThat(response.stderr()).isEqualTo("boom");
        assertThat(response.exitCode()).isEqualTo(1);
    }

    @Test
    void testInvokeMapsMemoryExceeded() {
        when(functionRegistryService.findFunction(function.getId()))
                .thenReturn(function);
        when(functionRegistryService.activeVersion(function)).thenReturn(version);
        when(judge0Client.createSubmission(any())).thenReturn("token-6");
        when(judge0Client.getSubmission("token-6")).thenReturn(new Judge0SubmissionResult(
                "token-6",
                7,
                "Runtime Error (Memory Limit Exceeded)",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        FunctionInvocationResponseDTO response = service.testInvoke(
                function.getId(),
                new FunctionTestInvocationRequestDTO(null, objectMapper.createObjectNode())
        );

        assertThat(response.status()).isEqualTo(FunctionInvocationStatus.FAILED);
        assertThat(response.errorName())
                .isEqualTo(TaskResourceErrors.FUNCTION_MEMORY_EXCEEDED);
    }

    @Test
    void invokeForTaskThrowsStableTimeoutError() {
        when(functionRegistryService.findFunction("billing", "tax"))
                .thenReturn(function);
        when(functionRegistryService.activeVersion(function)).thenReturn(version);
        when(judge0Client.createSubmission(any())).thenReturn("token-4");
        when(judge0Client.getSubmission("token-4")).thenReturn(new Judge0SubmissionResult(
                "token-4",
                5,
                "Time Limit Exceeded",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThatThrownBy(() -> service.invokeForTask(
                "billing",
                "tax",
                null,
                objectMapper.createObjectNode()
        ))
                .isInstanceOf(TaskResourceException.class)
                .extracting(error -> ((TaskResourceException) error).error())
                .isEqualTo(TaskResourceErrors.TIMEOUT);
    }

    @Test
    void runExecutesAdHocCodeAndParsesOutput() throws Exception {
        when(judge0Client.createSubmission(any())).thenReturn("run-1");
        when(judge0Client.getSubmission("run-1")).thenReturn(new Judge0SubmissionResult(
                "run-1", 3, "Accepted", "{\"tax\":18}", "", null, null,
                0, null, 0.01, 0.02, 12000L));

        FunctionRunResultDTO result = service.run(new FunctionRunRequestDTO(
                71, FunctionSourceMode.SINGLE_FILE, "print('{}')", null, null, null,
                null, null, null, null, 4096, false, objectMapper.readTree("{\"amount\":100}")));

        assertThat(result.status()).isEqualTo(FunctionInvocationStatus.SUCCEEDED);
        assertThat(result.output().get("tax").intValue()).isEqualTo(18);
        assertThat(result.errorName()).isNull();
    }

    @Test
    void runMapsRuntimeError() {
        when(judge0Client.createSubmission(any())).thenReturn("run-2");
        when(judge0Client.getSubmission("run-2")).thenReturn(new Judge0SubmissionResult(
                "run-2", 11, "Runtime Error (NZEC)", null, "boom", null, null,
                1, null, null, null, null));

        FunctionRunResultDTO result = service.run(new FunctionRunRequestDTO(
                71, FunctionSourceMode.SINGLE_FILE, "raise SystemExit(1)", null, null, null,
                null, null, null, null, 4096, false, objectMapper.createObjectNode()));

        assertThat(result.status()).isEqualTo(FunctionInvocationStatus.FAILED);
        assertThat(result.errorName()).isEqualTo(TaskResourceErrors.FUNCTION_RUNTIME_ERROR);
        assertThat(result.stderr()).isEqualTo("boom");
    }

    private FunctionDefinition function() {
        FunctionDefinition value = new FunctionDefinition();
        value.setId(UUID.randomUUID());
        value.setNamespace("billing");
        value.setName("tax");
        value.setActiveVersion(1);
        value.setStatus(FunctionStatus.ENABLED);
        return value;
    }

    private FunctionVersion version(FunctionDefinition functionDefinition) {
        FunctionVersion value = new FunctionVersion();
        value.setId(UUID.randomUUID());
        value.setFunctionDefinition(functionDefinition);
        value.setVersion(1);
        value.setSourceMode(FunctionSourceMode.SINGLE_FILE);
        value.setLanguageId(71);
        value.setSourceCode("print('{}')");
        value.setCpuTimeLimitSeconds(2.0);
        value.setWallTimeLimitSeconds(10.0);
        value.setMemoryLimitKb(131072);
        value.setMaxFileSizeKb(1024);
        value.setMaxOutputBytes(4096);
        value.setStatus(FunctionVersionStatus.AVAILABLE);
        return value;
    }
}
