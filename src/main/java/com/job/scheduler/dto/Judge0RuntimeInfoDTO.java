package com.job.scheduler.dto;

import java.util.List;

/**
 * Read-only snapshot of the Judge0 execution runtime for the self-hosted setup
 * view: reachability, worker/language/status counts, the available languages,
 * and the engine's configured ceilings. {@code reachable} is false (with an
 * {@code error} message) when Judge0 cannot be contacted, so the UI can render a
 * degraded state instead of failing.
 */
public record Judge0RuntimeInfoDTO(
        boolean reachable,
        String error,
        int languageCount,
        int statusCount,
        int workers,
        int availableWorkers,
        List<FunctionLanguageDTO> languages,
        Judge0LimitsDTO limits
) {
}
