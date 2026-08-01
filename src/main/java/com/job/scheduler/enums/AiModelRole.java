package com.job.scheduler.enums;

/**
 * The function a registered AI model serves. CHAT models generate, edit, and repair
 * workflows and act as evaluation judges; EMBEDDING models vectorize the resource
 * catalog for retrieval. {@code default_model} is scoped per role, so there is one
 * default chat model and one default embedding model.
 */
public enum AiModelRole {
    CHAT,
    EMBEDDING
}
