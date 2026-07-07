package com.job.scheduler.dto;

import com.job.scheduler.enums.FunctionStatus;

import java.time.Instant;
import java.util.UUID;

public record FunctionDefinitionResponseDTO(
        UUID id,
        String namespace,
        String name,
        String description,
        Integer activeVersion,
        FunctionStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
