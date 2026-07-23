package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowAiStreamEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pushes in-progress frames for a workflow-AI turn to the browser that asked for it.
 *
 * <p>The final {@code WorkflowAiResponseDTO} still travels over the {@code @SendToUser} reply, which
 * is request/response by construction and therefore cannot carry intermediate output. This broker is
 * the side channel for everything before that reply lands.
 *
 * <p>The subscribing session is bound to the calling thread for the duration of the turn rather than
 * threaded through {@code WorkflowAiConversationService}'s six public entry points. That is safe
 * because a STOMP {@code @MessageMapping} runs the whole turn synchronously on one thread, and it
 * keeps the REST entry points streaming-free: with no session bound, every emit is a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowAiStreamBroker {

    public static final String STREAM_DESTINATION = "/queue/workflow-ai-stream";

    private static final ThreadLocal<String> BOUND_SESSION = new ThreadLocal<>();

    private final SimpMessagingTemplate messagingTemplate;

    /** Runs {@code turn} with streaming directed at {@code sessionId}. */
    public <T> T withSession(String sessionId, Supplier<T> turn) {
        if (sessionId == null || sessionId.isBlank()) {
            return turn.get();
        }
        BOUND_SESSION.set(sessionId);
        try {
            return turn.get();
        } finally {
            BOUND_SESSION.remove();
        }
    }

    /**
     * The session bound to the calling thread, or {@code null}.
     *
     * <p>Callers that emit from another thread must capture this while still on the turn's thread
     * and pass it back explicitly: the streaming HTTP client delivers tokens on its own callback
     * thread, where the thread-bound value is not visible.
     */
    public String currentSession() {
        return BOUND_SESSION.get();
    }

    public void emitStage(String sessionId, UUID conversationId, String stage, int pass) {
        emit(sessionId, WorkflowAiStreamEventDTO.stage(conversationId, stage, pass));
    }

    public void emitThinking(String sessionId, UUID conversationId, String text, int pass) {
        emit(sessionId, WorkflowAiStreamEventDTO.thinking(conversationId, text, pass));
    }

    public void emitAnswerProgress(
            String sessionId,
            UUID conversationId,
            int answerCharacters,
            int pass
    ) {
        emit(sessionId, WorkflowAiStreamEventDTO.answerProgress(
                conversationId,
                answerCharacters,
                pass
        ));
    }

    public void emitError(String sessionId, UUID conversationId, String message) {
        emit(sessionId, WorkflowAiStreamEventDTO.error(conversationId, message));
    }

    private void emit(String sessionId, WorkflowAiStreamEventDTO event) {
        if (sessionId == null) {
            return;
        }
        try {
            SimpMessageHeaderAccessor accessor =
                    SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setSessionId(sessionId);
            accessor.setLeaveMutable(true);
            messagingTemplate.convertAndSendToUser(
                    sessionId,
                    STREAM_DESTINATION,
                    event,
                    accessor.getMessageHeaders()
            );
        } catch (Exception exception) {
            // A dropped subscriber must never fail the turn: the authoritative response is still
            // persisted and returned over the @SendToUser reply.
            log.debug("Could not publish workflow AI stream event", exception);
        }
    }
}
