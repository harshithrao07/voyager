package com.job.scheduler.dto;

import java.util.List;

public record WorkflowPageDTO(
        List<WorkflowResponseDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
