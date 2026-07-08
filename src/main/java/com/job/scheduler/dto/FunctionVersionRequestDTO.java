package com.job.scheduler.dto;

import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionVersionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FunctionVersionRequestDTO(
        FunctionSourceMode sourceMode,

        @NotNull
        @Min(1)
        Integer languageId,

        // Hard cap on single-file source (256 KiB) to bound submission size.
        @Size(max = 262_144, message = "sourceCode must be at most 262144 characters")
        String sourceCode,

        // Hard cap on the base64-encoded multi-file bundle (~4 MiB encoded).
        @Size(max = 4_194_304, message = "additionalFilesBase64 must be at most 4194304 characters")
        String additionalFilesBase64,

        String compilerOptions,

        String commandLineArguments,

        @DecimalMin(value = "0.1")
        Double cpuTimeLimitSeconds,

        @DecimalMin(value = "0.1")
        Double wallTimeLimitSeconds,

        @Min(1024)
        Integer memoryLimitKb,

        @Min(1)
        Integer maxFileSizeKb,

        @Min(1)
        Integer maxOutputBytes,

        Boolean enableNetwork,

        @Size(max = 2_000, message = "note must be at most 2000 characters")
        String note,

        FunctionVersionStatus status
) {
}
