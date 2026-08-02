package com.job.scheduler.dto;

/**
 * A model reported by a provider's {@code /models} listing that can be onboarded.
 * {@code alreadyAdded} is true when a model with this name already exists for the endpoint,
 * letting the UI pre-mark it so the user only picks new ones.
 */
public record AiModelAvailableDTO(
        String modelName,
        boolean alreadyAdded
) {
}
