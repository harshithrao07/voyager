package com.job.scheduler.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class TaskAttemptHeartbeatService {
    private final StateExecutionAttemptQueueService queueService;
    private final long maximumPulseIntervalMs;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "workflow-task-heartbeat"
                );
                thread.setDaemon(true);
                return thread;
            });

    public TaskAttemptHeartbeatService(
            StateExecutionAttemptQueueService queueService,
            @Value("${scheduler.workflow.task-heartbeat-max-pulse-ms:5000}")
            long maximumPulseIntervalMs
    ) {
        if (maximumPulseIntervalMs <= 0) {
            throw new IllegalArgumentException(
                    "Task heartbeat pulse interval must be positive"
            );
        }
        this.queueService = queueService;
        this.maximumPulseIntervalMs = maximumPulseIntervalMs;
    }

    public HeartbeatLease start(
            UUID attemptId,
            String workerId,
            Long heartbeatSeconds
    ) {
        if (heartbeatSeconds == null) {
            return () -> {
            };
        }
        long intervalMs = Math.max(
                100L,
                Math.min(maximumPulseIntervalMs, heartbeatSeconds * 500L)
        );
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> queueService.recordHeartbeat(attemptId, workerId),
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        return () -> future.cancel(false);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    @FunctionalInterface
    public interface HeartbeatLease extends AutoCloseable {
        @Override
        void close();
    }
}
