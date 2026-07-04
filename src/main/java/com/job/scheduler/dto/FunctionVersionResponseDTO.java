package com.job.scheduler.dto;

import com.job.scheduler.enums.FunctionSourceMode;
import com.job.scheduler.enums.FunctionVersionStatus;

import java.time.Instant;
import java.util.UUID;

public record FunctionVersionResponseDTO(
        UUID id,
        UUID functionId,
        int version,
        FunctionSourceMode sourceMode,
        int languageId,
        boolean hasSourceCode,
        boolean hasAdditionalFiles,
        String compilerOptions,
        String commandLineArguments,
        double cpuTimeLimitSeconds,
        double wallTimeLimitSeconds,
        int memoryLimitKb,
        int maxFileSizeKb,
        int maxOutputBytes,
        boolean enableNetwork,
        FunctionVersionStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
