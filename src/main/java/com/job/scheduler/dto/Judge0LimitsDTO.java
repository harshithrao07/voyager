package com.job.scheduler.dto;

/**
 * Effective sandbox ceilings reported by Judge0 ({@code GET /config_info}). These
 * are operator-owned deploy-time limits (set in judge0.conf); the app surfaces
 * them read-only so a self-hoster can see what their engine allows.
 */
public record Judge0LimitsDTO(
        Double cpuTimeLimit,
        Double maxCpuTimeLimit,
        Double wallTimeLimit,
        Double maxWallTimeLimit,
        Long memoryLimit,
        Long maxMemoryLimit,
        Integer maxFileSize,
        Integer maxExtractSize,
        Boolean enableNetwork,
        Boolean allowEnableNetwork
) {
}
