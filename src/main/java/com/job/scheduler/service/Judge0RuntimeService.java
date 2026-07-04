package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.Judge0LimitsDTO;
import com.job.scheduler.dto.Judge0RuntimeInfoDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Assembles a read-only view of the Judge0 execution runtime for the self-hosted
 * setup screen. A Judge0 outage is expected (functions are optional), so failures
 * are folded into a {@code reachable=false} response rather than propagated.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class Judge0RuntimeService {
    private final Judge0Client judge0Client;

    public Judge0RuntimeInfoDTO runtimeInfo() {
        try {
            List<FunctionLanguageDTO> languages = judge0Client.listLanguages();
            int statuses = judge0Client.countStatuses();
            Judge0Client.WorkerStats workers = judge0Client.workerStats();
            Judge0LimitsDTO limits = judge0Client.configInfo();
            return new Judge0RuntimeInfoDTO(
                    true,
                    null,
                    languages.size(),
                    statuses,
                    workers.total(),
                    workers.available(),
                    languages,
                    limits
            );
        } catch (RuntimeException exception) {
            log.warn("Judge0 runtime info unavailable: {}", exception.getMessage());
            return new Judge0RuntimeInfoDTO(
                    false,
                    exception.getMessage(),
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    null
            );
        }
    }
}
