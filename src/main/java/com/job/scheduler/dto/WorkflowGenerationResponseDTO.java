package com.job.scheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowGenerationResponseDTO {
    private JsonNode definition;
    private String rawOutput;
    private List<String> validationIssues;
}
