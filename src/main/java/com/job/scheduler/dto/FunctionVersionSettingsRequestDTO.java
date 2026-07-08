package com.job.scheduler.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FunctionVersionSettingsRequestDTO(
        @Size(max = 512)
        String compilerOptions,

        @Size(max = 512)
        String commandLineArguments,

        @NotNull
        @DecimalMin(value = "0.1")
        Double cpuTimeLimitSeconds,

        @NotNull
        @DecimalMin(value = "0.1")
        Double wallTimeLimitSeconds,

        @NotNull
        @Min(1024)
        Integer memoryLimitKb,

        @NotNull
        @Min(1)
        Integer maxFileSizeKb,

        @NotNull
        @Min(1)
        Integer maxOutputBytes,

        @NotNull
        Boolean enableNetwork
) {
}
