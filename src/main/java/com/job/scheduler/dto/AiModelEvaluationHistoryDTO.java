package com.job.scheduler.dto;

import java.util.List;

public record AiModelEvaluationHistoryDTO(
        List<AiModelEvaluationDTO> runs,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
