package com.job.scheduler.dto;

import com.job.scheduler.enums.FunctionInvocationStatus;
import tools.jackson.databind.JsonNode;

/**
 * Result of an ad-hoc function run (see {@link FunctionRunRequestDTO}). Not
 * persisted — mirrors the relevant fields of an invocation so the editor can show
 * stdout/stderr, the parsed JSON output, and a stable error classification.
 */
public record FunctionRunResultDTO(
        FunctionInvocationStatus status,
        JsonNode output,
        String stdout,
        String stderr,
        String compileOutput,
        String message,
        Integer exitCode,
        Integer judge0StatusId,
        String judge0StatusDescription,
        String errorName,
        String errorMessage,
        Double timeSeconds,
        Long memoryKb
) {
}
