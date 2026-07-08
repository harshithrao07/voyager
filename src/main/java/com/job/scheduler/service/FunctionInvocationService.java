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
import com.job.scheduler.workflow.task.TaskExecutionContext;
import com.job.scheduler.workflow.task.TaskResourceErrors;
import com.job.scheduler.workflow.task.TaskResourceException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FunctionInvocationService {
    private final FunctionRegistryService functionRegistryService;
    private final FunctionInvocationRepository invocationRepository;
    private final Judge0Client judge0Client;
    private final Judge0MultiFileSupport multiFileSupport;
    private final ObjectMapper objectMapper;

    @Value("${scheduler.judge0.poll-interval-ms:500}")
    private long pollIntervalMs;

    @Value("${scheduler.judge0.max-poll-duration-ms:60000}")
    private long maxPollDurationMs;

    @Value("${scheduler.judge0.default-cpu-time-limit-seconds:2.0}")
    private double defaultCpuTimeLimitSeconds;

    @Value("${scheduler.judge0.default-wall-time-limit-seconds:10.0}")
    private double defaultWallTimeLimitSeconds;

    @Value("${scheduler.judge0.default-memory-limit-kb:131072}")
    private int defaultMemoryLimitKb;

    @Value("${scheduler.judge0.default-max-file-size-kb:1024}")
    private int defaultMaxFileSizeKb;

    @Value("${scheduler.judge0.default-max-output-bytes:65536}")
    private int defaultMaxOutputBytes;

    /**
     * Executes ad-hoc code (no persisted function/version) so the editor can run
     * test cases before publishing. Same stdin-JSON/stdout-JSON contract and error
     * mapping as a real invocation, but nothing is saved.
     */
    public FunctionRunResultDTO run(FunctionRunRequestDTO request) {
        FunctionSourceMode mode = request.sourceMode() == null
                ? FunctionSourceMode.SINGLE_FILE
                : request.sourceMode();
        int maxOutputBytes = request.maxOutputBytes() != null
                ? request.maxOutputBytes()
                : defaultMaxOutputBytes;
        int memoryLimitKb = request.memoryLimitKb() != null
                ? request.memoryLimitKb()
                : defaultMemoryLimitKb;
        try {
            int submissionLanguageId = request.languageId();
            String sourceCode = mode == FunctionSourceMode.SINGLE_FILE ? blankToNull(request.sourceCode()) : null;
            String additionalFiles = mode == FunctionSourceMode.MULTI_FILE ? blankToNull(request.additionalFilesBase64()) : null;
            String compilerOptions = blankToNull(request.compilerOptions());
            String commandLineArguments = blankToNull(request.commandLineArguments());
            if (mode == FunctionSourceMode.MULTI_FILE) {
                // Rewrite to a Judge0 "Multi-file program" with generated compile/run
                // scripts; the language-specific behaviour is baked into the archive.
                additionalFiles = multiFileSupport.buildArchive(
                        request.languageId(), additionalFiles, compilerOptions, commandLineArguments);
                submissionLanguageId = Judge0MultiFileSupport.MULTI_FILE_LANGUAGE_ID;
                compilerOptions = null;
                commandLineArguments = null;
            }
            Judge0SubmissionRequest submission = new Judge0SubmissionRequest(
                    submissionLanguageId,
                    sourceCode,
                    additionalFiles,
                    nonNullInput(request.input()).toString(),
                    compilerOptions,
                    commandLineArguments,
                    request.cpuTimeLimitSeconds() != null ? request.cpuTimeLimitSeconds() : defaultCpuTimeLimitSeconds,
                    request.wallTimeLimitSeconds() != null ? request.wallTimeLimitSeconds() : defaultWallTimeLimitSeconds,
                    memoryLimitKb,
                    request.maxFileSizeKb() != null ? request.maxFileSizeKb() : defaultMaxFileSizeKb,
                    Boolean.TRUE.equals(request.enableNetwork())
            );

            String token = judge0Client.createSubmission(submission);
            Judge0SubmissionResult result = pollUntilComplete(token);
            String stdout = truncate(result.stdout(), maxOutputBytes);
            String stderr = truncate(result.stderr(), maxOutputBytes);
            String compileOutput = truncate(result.compileOutput(), maxOutputBytes);
            String message = truncate(result.message(), maxOutputBytes);

            if (result.isAccepted()) {
                JsonNode output = null;
                String errorName = null;
                String errorMessage = null;
                FunctionInvocationStatus status;
                if (stdout == null || stdout.isBlank()) {
                    status = FunctionInvocationStatus.FAILED;
                    errorName = TaskResourceErrors.FUNCTION_INVALID_OUTPUT;
                    errorMessage = "Function stdout must contain JSON";
                } else {
                    try {
                        output = objectMapper.readTree(stdout.trim());
                        status = FunctionInvocationStatus.SUCCEEDED;
                    } catch (Exception exception) {
                        status = FunctionInvocationStatus.FAILED;
                        errorName = TaskResourceErrors.FUNCTION_INVALID_OUTPUT;
                        errorMessage = "Function stdout must be valid JSON";
                    }
                }
                return new FunctionRunResultDTO(status, output, stdout, stderr, compileOutput,
                        message, result.exitCode(), result.statusId(), result.statusDescription(),
                        errorName, errorMessage, result.timeSeconds(), result.memoryKb());
            }

            return new FunctionRunResultDTO(FunctionInvocationStatus.FAILED, null, stdout, stderr,
                    compileOutput, message, result.exitCode(), result.statusId(),
                    result.statusDescription(), adHocErrorName(memoryLimitKb, result),
                    errorMessageFor(result), result.timeSeconds(), result.memoryKb());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new FunctionRunResultDTO(FunctionInvocationStatus.FAILED, null, null, null, null,
                    "Run was interrupted", null, null, null, TaskResourceErrors.TIMEOUT,
                    "Run was interrupted", null, null);
        } catch (TaskResourceException exception) {
            return new FunctionRunResultDTO(FunctionInvocationStatus.FAILED, null, null, null, null,
                    exception.getMessage(), null, null, null, exception.error(),
                    exception.getMessage(), null, null);
        } catch (RuntimeException exception) {
            // Judge0 outages / rejected submissions / malformed responses must not
            // surface as a 500 from the editor's run endpoint.
            return new FunctionRunResultDTO(FunctionInvocationStatus.FAILED, null, null, null, null,
                    "Execution backend error: " + exception.getMessage(), null, null, null,
                    TaskResourceErrors.FUNCTION_RUNTIME_ERROR,
                    "Could not run the function. The execution backend rejected the request or is unavailable.",
                    null, null);
        }
    }

    private String adHocErrorName(int memoryLimitKb, Judge0SubmissionResult result) {
        Integer statusId = result.statusId();
        if (statusId != null && statusId == 6) {
            return TaskResourceErrors.FUNCTION_COMPILE_ERROR;
        }
        if (statusId != null && statusId == 5) {
            return TaskResourceErrors.TIMEOUT;
        }
        String description = result.statusDescription() == null
                ? "" : result.statusDescription().toLowerCase(Locale.ROOT);
        String message = result.message() == null
                ? "" : result.message().toLowerCase(Locale.ROOT);
        Long usedKb = result.memoryKb();
        if (description.contains("memory") || message.contains("memory")
                || (usedKb != null && memoryLimitKb > 0 && usedKb >= memoryLimitKb)) {
            return TaskResourceErrors.FUNCTION_MEMORY_EXCEEDED;
        }
        return TaskResourceErrors.FUNCTION_RUNTIME_ERROR;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public JsonNode invokeForTask(
            String namespace,
            String name,
            Integer requestedVersion,
            JsonNode input
    ) {
        return invokeForTask(
                namespace,
                name,
                requestedVersion,
                input,
                TaskExecutionContext.NONE
        );
    }

    public JsonNode invokeForTask(
            String namespace,
            String name,
            Integer requestedVersion,
            JsonNode input,
            TaskExecutionContext context
    ) {
        try {
            FunctionDefinition function =
                    functionRegistryService.findFunction(namespace, name);
            FunctionVersion version = requestedVersion == null
                    ? functionRegistryService.activeVersion(function)
                    : functionRegistryService.findVersion(function, requestedVersion);
            CompletedInvocation completed = execute(function, version, input, context);
            if (completed.invocation().getStatus()
                    == FunctionInvocationStatus.SUCCEEDED) {
                return completed.output();
            }
            throw toTaskResourceException(completed.invocation());
        } catch (TaskResourceException exception) {
            throw exception;
        } catch (EntityNotFoundException exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.FUNCTION_NOT_FOUND,
                    exception.getMessage(),
                    exception
            );
        } catch (IllegalStateException exception) {
            String message = exception.getMessage() == null
                    ? ""
                    : exception.getMessage().toLowerCase(Locale.ROOT);
            throw new TaskResourceException(
                    message.contains("disabled")
                            ? TaskResourceErrors.PERMISSIONS
                            : TaskResourceErrors.FUNCTION_NOT_FOUND,
                    exception.getMessage(),
                    exception
            );
        }
    }

    public FunctionInvocationResponseDTO testInvoke(
            UUID functionId,
            FunctionTestInvocationRequestDTO request
    ) {
        FunctionDefinition function = functionRegistryService.findFunction(functionId);
        FunctionVersion version = request.version() == null
                ? functionRegistryService.activeVersion(function)
                : functionRegistryService.findVersion(function, request.version());
        CompletedInvocation completed = execute(
                function,
                version,
                request.input(),
                TaskExecutionContext.NONE
        );
        return toResponse(completed.invocation());
    }

    public List<FunctionInvocationResponseDTO> getInvocations(UUID functionId) {
        FunctionDefinition function = functionRegistryService.findFunction(functionId);
        return invocationRepository
                .findByFunctionDefinitionOrderByStartedAtDesc(function)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private CompletedInvocation execute(
            FunctionDefinition function,
            FunctionVersion version,
            JsonNode input,
            TaskExecutionContext context
    ) {
        validateInvokable(function, version);
        TaskExecutionContext runContext = context == null
                ? TaskExecutionContext.NONE
                : context;
        Instant startedAt = Instant.now();
        FunctionInvocation invocation = new FunctionInvocation();
        invocation.setFunctionDefinition(function);
        invocation.setFunctionVersion(version);
        invocation.setWorkflowExecutionId(runContext.workflowExecutionId());
        invocation.setStateName(runContext.stateName());
        invocation.setStatus(FunctionInvocationStatus.RUNNING);
        invocation.setInputJson(writeJson(nonNullInput(input)));
        invocation.setStartedAt(startedAt);
        invocation = invocationRepository.save(invocation);

        try {
            String token = judge0Client.createSubmission(toSubmissionRequest(
                    version,
                    nonNullInput(input)
            ));
            invocation.setJudge0Token(token);
            invocationRepository.save(invocation);

            Judge0SubmissionResult result = pollUntilComplete(token);
            applyJudge0Result(invocation, result);
            JsonNode output = null;
            if (result.isAccepted()) {
                output = parseOutput(
                        invocation,
                        result.stdout(),
                        version.getMaxOutputBytes()
                );
                if (output != null) {
                    invocation.setOutputJson(writeJson(output));
                    markSuccess(invocation);
                }
            } else {
                markFailed(
                        invocation,
                        errorNameFor(invocation, result),
                        errorMessageFor(result)
                );
            }
            return new CompletedInvocation(
                    invocationRepository.save(invocation),
                    output
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(invocation, TaskResourceErrors.TIMEOUT,
                    "Function invocation was interrupted");
            return new CompletedInvocation(invocationRepository.save(invocation), null);
        } catch (TaskResourceException exception) {
            markFailed(invocation, exception.error(), exception.getMessage());
            return new CompletedInvocation(invocationRepository.save(invocation), null);
        } catch (RuntimeException exception) {
            markFailed(
                    invocation,
                    errorNameForPlatformFailure(exception),
                    exception.getMessage()
            );
            return new CompletedInvocation(invocationRepository.save(invocation), null);
        }
    }

    private void validateInvokable(
            FunctionDefinition function,
            FunctionVersion version
    ) {
        if (function.getStatus() != FunctionStatus.ENABLED) {
            throw new IllegalStateException(
                    "Function is disabled: "
                            + function.getNamespace() + "/" + function.getName()
            );
        }
        if (version.getStatus() != FunctionVersionStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Function version is not available: " + version.getVersion()
            );
        }
    }

    private Judge0SubmissionRequest toSubmissionRequest(
            FunctionVersion version,
            JsonNode input
    ) {
        boolean multiFile = version.getSourceMode() == FunctionSourceMode.MULTI_FILE;
        int languageId = version.getLanguageId();
        String sourceCode = multiFile ? null : version.getSourceCode();
        String additionalFiles = multiFile ? version.getAdditionalFilesBase64() : null;
        String compilerOptions = version.getCompilerOptions();
        String commandLineArguments = version.getCommandLineArguments();
        if (multiFile) {
            // Submit as a Judge0 "Multi-file program" with generated compile/run
            // scripts for the version's language.
            additionalFiles = multiFileSupport.buildArchive(
                    languageId, additionalFiles, compilerOptions, commandLineArguments);
            languageId = Judge0MultiFileSupport.MULTI_FILE_LANGUAGE_ID;
            compilerOptions = null;
            commandLineArguments = null;
        }
        return new Judge0SubmissionRequest(
                languageId,
                sourceCode,
                additionalFiles,
                input.toString(),
                compilerOptions,
                commandLineArguments,
                version.getCpuTimeLimitSeconds(),
                version.getWallTimeLimitSeconds(),
                version.getMemoryLimitKb(),
                version.getMaxFileSizeKb(),
                version.isEnableNetwork()
        );
    }

    private Judge0SubmissionResult pollUntilComplete(String token)
            throws InterruptedException {
        Instant deadline = Instant.now().plusMillis(maxPollDurationMs);
        while (Instant.now().isBefore(deadline)) {
            Judge0SubmissionResult result = judge0Client.getSubmission(token);
            if (!result.isProcessing()) {
                return result;
            }
            Thread.sleep(Math.max(50, pollIntervalMs));
        }
        throw new TaskResourceException(
                TaskResourceErrors.TIMEOUT,
                "Function invocation timed out while waiting for Judge0"
        );
    }

    private void applyJudge0Result(
            FunctionInvocation invocation,
            Judge0SubmissionResult result
    ) {
        invocation.setJudge0StatusId(result.statusId());
        invocation.setJudge0StatusDescription(result.statusDescription());
        invocation.setStdout(truncate(result.stdout(),
                invocation.getFunctionVersion().getMaxOutputBytes()));
        invocation.setStderr(truncate(result.stderr(),
                invocation.getFunctionVersion().getMaxOutputBytes()));
        invocation.setCompileOutput(truncate(result.compileOutput(),
                invocation.getFunctionVersion().getMaxOutputBytes()));
        invocation.setMessage(truncate(result.message(),
                invocation.getFunctionVersion().getMaxOutputBytes()));
        invocation.setExitCode(result.exitCode());
        invocation.setExitSignal(result.exitSignal());
        invocation.setTimeSeconds(result.timeSeconds());
        invocation.setWallTimeSeconds(result.wallTimeSeconds());
        invocation.setMemoryKb(result.memoryKb());
    }

    private JsonNode parseOutput(
            FunctionInvocation invocation,
            String stdout,
            int maxOutputBytes
    ) {
        String normalized = truncate(stdout, maxOutputBytes);
        if (normalized == null || normalized.isBlank()) {
            markFailed(
                    invocation,
                    TaskResourceErrors.FUNCTION_INVALID_OUTPUT,
                    "Function stdout must contain JSON"
            );
            return null;
        }
        try {
            return objectMapper.readTree(normalized.trim());
        } catch (Exception exception) {
            markFailed(
                    invocation,
                    TaskResourceErrors.FUNCTION_INVALID_OUTPUT,
                    "Function stdout must be valid JSON"
            );
            return null;
        }
    }

    private String errorNameFor(
            FunctionInvocation invocation,
            Judge0SubmissionResult result
    ) {
        Integer statusId = result.statusId();
        // Map on Judge0 status ids, not on the description text: every runtime
        // status reads "Runtime Error (...)", and the word "runtime" contains the
        // substring "time", so a description match would misclassify them all as
        // States.Timeout. Status 6 = Compilation Error, 5 = Time Limit Exceeded.
        if (statusId != null && statusId == 6) {
            return TaskResourceErrors.FUNCTION_COMPILE_ERROR;
        }
        if (statusId != null && statusId == 5) {
            return TaskResourceErrors.TIMEOUT;
        }
        if (indicatesMemoryExhaustion(invocation, result)) {
            return TaskResourceErrors.FUNCTION_MEMORY_EXCEEDED;
        }
        return TaskResourceErrors.FUNCTION_RUNTIME_ERROR;
    }

    /**
     * Judge0 has no dedicated "memory limit exceeded" status; an over-limit run
     * usually surfaces as a runtime error (SIGSEGV/SIGKILL). Treat it as a memory
     * failure when the description/message says so, or when reported memory usage
     * reached the version's configured limit.
     */
    private boolean indicatesMemoryExhaustion(
            FunctionInvocation invocation,
            Judge0SubmissionResult result
    ) {
        String description = result.statusDescription() == null
                ? ""
                : result.statusDescription().toLowerCase(Locale.ROOT);
        String message = result.message() == null
                ? ""
                : result.message().toLowerCase(Locale.ROOT);
        if (description.contains("memory") || message.contains("memory")) {
            return true;
        }
        int limitKb = invocation.getFunctionVersion().getMemoryLimitKb();
        Long usedKb = result.memoryKb();
        return usedKb != null && limitKb > 0 && usedKb >= limitKb;
    }

    private String errorMessageFor(Judge0SubmissionResult result) {
        if (result.statusDescription() != null
                && !result.statusDescription().isBlank()) {
            return result.statusDescription();
        }
        if (result.message() != null && !result.message().isBlank()) {
            return result.message();
        }
        return "Function execution failed";
    }

    private String errorNameForPlatformFailure(RuntimeException exception) {
        if (exception instanceof HttpStatusCodeException statusException
                && (statusException.getStatusCode().value() == 401
                || statusException.getStatusCode().value() == 403)) {
            return TaskResourceErrors.PERMISSIONS;
        }
        return TaskResourceErrors.FUNCTION_PLATFORM_ERROR;
    }

    private void markSuccess(FunctionInvocation invocation) {
        invocation.setStatus(FunctionInvocationStatus.SUCCEEDED);
        invocation.setErrorName(null);
        invocation.setErrorMessage(null);
        complete(invocation);
    }

    private void markFailed(
            FunctionInvocation invocation,
            String errorName,
            String errorMessage
    ) {
        invocation.setStatus(FunctionInvocationStatus.FAILED);
        invocation.setErrorName(errorName);
        invocation.setErrorMessage(errorMessage);
        complete(invocation);
    }

    private void complete(FunctionInvocation invocation) {
        Instant completedAt = Instant.now();
        invocation.setCompletedAt(completedAt);
        invocation.setDurationMs(
                Duration.between(invocation.getStartedAt(), completedAt)
                        .toMillis()
        );
    }

    private TaskResourceException toTaskResourceException(
            FunctionInvocation invocation
    ) {
        ObjectNode detail = JsonNodeFactory.instance.objectNode();
        if (invocation.getJudge0Token() != null) {
            detail.put("judge0Token", invocation.getJudge0Token());
        }
        if (invocation.getJudge0StatusId() != null) {
            detail.put("judge0StatusId", invocation.getJudge0StatusId());
        }
        if (invocation.getJudge0StatusDescription() != null) {
            detail.put(
                    "judge0StatusDescription",
                    invocation.getJudge0StatusDescription()
            );
        }
        if (invocation.getExitCode() != null) {
            detail.put("exitCode", invocation.getExitCode());
        }
        if (invocation.getStdout() != null) {
            detail.put("stdout", invocation.getStdout());
        }
        if (invocation.getStderr() != null) {
            detail.put("stderr", invocation.getStderr());
        }
        if (invocation.getCompileOutput() != null) {
            detail.put("compileOutput", invocation.getCompileOutput());
        }
        return new TaskResourceException(
                invocation.getErrorName() == null
                        ? TaskResourceErrors.FUNCTION_RUNTIME_ERROR
                        : invocation.getErrorName(),
                invocation.getErrorMessage() == null
                        ? "Function execution failed"
                        : invocation.getErrorMessage(),
                detail
        );
    }

    private FunctionInvocationResponseDTO toResponse(FunctionInvocation invocation) {
        return new FunctionInvocationResponseDTO(
                invocation.getId(),
                invocation.getFunctionDefinition().getId(),
                invocation.getFunctionVersion().getVersion(),
                invocation.getWorkflowExecutionId(),
                invocation.getStateName(),
                invocation.getJudge0Token(),
                invocation.getStatus(),
                readJson(invocation.getInputJson()),
                readJson(invocation.getOutputJson()),
                invocation.getStdout(),
                invocation.getStderr(),
                invocation.getCompileOutput(),
                invocation.getMessage(),
                invocation.getExitCode(),
                invocation.getExitSignal(),
                invocation.getJudge0StatusId(),
                invocation.getJudge0StatusDescription(),
                invocation.getErrorName(),
                invocation.getErrorMessage(),
                invocation.getTimeSeconds(),
                invocation.getWallTimeSeconds(),
                invocation.getMemoryKb(),
                invocation.getStartedAt(),
                invocation.getCompletedAt(),
                invocation.getDurationMs(),
                invocation.getCreatedAt(),
                invocation.getUpdatedAt()
        );
    }

    private JsonNode nonNullInput(JsonNode input) {
        return input == null ? objectMapper.createObjectNode() : input;
    }

    private JsonNode readJson(String value) {
        try {
            return value == null ? null : objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read function JSON", exception);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Could not serialize function JSON",
                    exception
            );
        }
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private record CompletedInvocation(
            FunctionInvocation invocation,
            JsonNode output
    ) {
    }
}
