package com.job.scheduler.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks the workflow-AI turn currently running for each STOMP session so it can be cancelled when
 * the browser that started it goes away.
 *
 * <p>A turn runs synchronously on one STOMP worker thread. {@link #register} captures that thread
 * while the turn owns it; {@link #cancel}, called from the session-disconnect listener on another
 * thread, both flips a cooperative flag and interrupts the worker. The turn checks the flag at each
 * model call and while waiting on a stream, so it aborts promptly and its transaction rolls back.
 */
@Component
public class WorkflowAiTurnRegistry {

    private final Map<String, Registration> active = new ConcurrentHashMap<>();

    /** Binds the calling (turn) thread to {@code sessionId} until the returned handle is closed. */
    public Registration register(String sessionId) {
        Registration registration = new Registration(sessionId, Thread.currentThread());
        active.put(sessionId, registration);
        return registration;
    }

    /** Requests cancellation of the turn bound to {@code sessionId}, if any. */
    public void cancel(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Registration registration = active.get(sessionId);
        if (registration != null) {
            registration.cancel();
        }
    }

    public boolean isCancelled(String sessionId) {
        Registration registration = sessionId == null ? null : active.get(sessionId);
        return registration != null && registration.cancelled.get();
    }

    /**
     * Aborts the current turn if its session was cancelled. Clears the thread's interrupt flag first
     * so the pooled worker starts its next task clean.
     */
    public void throwIfCancelled(String sessionId) {
        if (isCancelled(sessionId)) {
            Thread.interrupted();
            throw new WorkflowAiCancelledException();
        }
    }

    public final class Registration implements AutoCloseable {
        private final String sessionId;
        private final Thread thread;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private Registration(String sessionId, Thread thread) {
            this.sessionId = sessionId;
            this.thread = thread;
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                thread.interrupt();
            }
        }

        @Override
        public void close() {
            // Remove only our own mapping; a reused session id may already own a newer registration.
            active.remove(sessionId, this);
        }
    }
}
