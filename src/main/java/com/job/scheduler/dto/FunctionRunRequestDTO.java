package com.job.scheduler.dto;

import com.job.scheduler.enums.FunctionSourceMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Ad-hoc execution request used by the function editor to run test cases against
 * unsaved code (no persisted function/version). Runs the code through Judge0 with
 * the same stdin-JSON in / stdout-JSON out contract as a real invocation.
 */
public record FunctionRunRequestDTO(
        @NotNull
        @Min(1)
        Integer languageId,

        FunctionSourceMode sourceMode,

        @Size(max = 262_144)
        String sourceCode,

        @Size(max = 4_194_304)
        String additionalFilesBase64,

        String compilerOptions,

        String commandLineArguments,

        Double cpuTimeLimitSeconds,

        Double wallTimeLimitSeconds,

        Integer memoryLimitKb,

        Integer maxFileSizeKb,

        Integer maxOutputBytes,

        Boolean enableNetwork,

        JsonNode input
) {
}
