package com.job.scheduler.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowAiTurnRegistryTest {

    private static final String SESSION_ID = "stomp-session-1";

    private final WorkflowAiTurnRegistry registry = new WorkflowAiTurnRegistry();

    @Test
    void throwsForACancelledSessionAndClearsTheInterrupt() {
        try (WorkflowAiTurnRegistry.Registration ignored = registry.register(SESSION_ID)) {
            registry.cancel(SESSION_ID);

            assertThat(registry.isCancelled(SESSION_ID)).isTrue();
            assertThatThrownBy(() -> registry.throwIfCancelled(SESSION_ID))
                    .isInstanceOf(WorkflowAiCancelledException.class);
            // The cancel interrupts the turn thread; throwIfCancelled must consume that interrupt so
            // the pooled worker does not carry it into its next task.
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }
    }

    @Test
    void ignoresCancelForAnUnknownOrCompletedSession() {
        // Never registered.
        registry.cancel("missing");
        assertThat(registry.isCancelled("missing")).isFalse();

        // Registered then completed (handle closed): a late disconnect must be a no-op.
        try (WorkflowAiTurnRegistry.Registration ignored = registry.register(SESSION_ID)) {
            assertThat(registry.isCancelled(SESSION_ID)).isFalse();
        }
        registry.cancel(SESSION_ID);
        assertThat(registry.isCancelled(SESSION_ID)).isFalse();
    }

    @Test
    void throwIfCancelledIsANoOpForNullOrLiveSessions() {
        registry.throwIfCancelled(null);
        try (WorkflowAiTurnRegistry.Registration ignored = registry.register(SESSION_ID)) {
            // Not cancelled: the running turn keeps going.
            registry.throwIfCancelled(SESSION_ID);
        }
    }
}
