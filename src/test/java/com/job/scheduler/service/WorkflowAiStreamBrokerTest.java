package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowAiStreamEventDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkflowAiStreamBrokerTest {

    private static final String SESSION_ID = "stomp-session-1";

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private WorkflowAiTurnRegistry turnRegistry;

    @InjectMocks
    private WorkflowAiStreamBroker broker;

    private final UUID conversationId = UUID.randomUUID();

    @Test
    void routesEventsToTheSubscribingSession() {
        broker.withSession(SESSION_ID, () -> {
            broker.emitStage(broker.currentSession(), conversationId, "Designing the workflow", 1);
            return null;
        });

        ArgumentCaptor<WorkflowAiStreamEventDTO> event =
                ArgumentCaptor.forClass(WorkflowAiStreamEventDTO.class);
        ArgumentCaptor<MessageHeaders> headers = ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(SESSION_ID),
                eq(WorkflowAiStreamBroker.STREAM_DESTINATION),
                event.capture(),
                headers.capture()
        );
        assertThat(event.getValue().type()).isEqualTo(WorkflowAiStreamEventDTO.Type.STAGE);
        assertThat(event.getValue().stage()).isEqualTo("Designing the workflow");
        // Without the session header the user destination cannot be resolved for an anonymous
        // STOMP session and the frame is dropped.
        assertThat(headers.getValue().get(SimpMessageHeaderAccessor.SESSION_ID_HEADER))
                .isEqualTo(SESSION_ID);
    }

    @Test
    void emitsFromAnotherThreadWhenGivenTheCapturedSession() throws Exception {
        // Streamed tokens are delivered on the HTTP client's thread, not the turn's thread.
        String captured = broker.withSession(SESSION_ID, broker::currentSession);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> emitted = executor.submit(
                    () -> broker.emitThinking(captured, conversationId, "weighing options", 1)
            );
            emitted.get();
        } finally {
            executor.shutdownNow();
        }

        verify(messagingTemplate).convertAndSendToUser(
                eq(SESSION_ID),
                eq(WorkflowAiStreamBroker.STREAM_DESTINATION),
                any(WorkflowAiStreamEventDTO.class),
                any(MessageHeaders.class)
        );
    }

    @Test
    void unbindsTheSessionAfterTheTurn() {
        broker.withSession(SESSION_ID, () -> null);

        assertThat(broker.currentSession()).isNull();
    }

    @Test
    void unbindsTheSessionWhenTheTurnThrows() {
        try {
            broker.withSession(SESSION_ID, () -> {
                throw new IllegalStateException("turn failed");
            });
        } catch (IllegalStateException ignored) {
            // asserted below
        }

        assertThat(broker.currentSession()).isNull();
    }

    @Test
    void skipsPublishingWhenNobodyIsSubscribed() {
        broker.emitThinking(broker.currentSession(), conversationId, "orphaned", 1);

        verify(messagingTemplate, never()).convertAndSendToUser(
                any(),
                any(),
                any(),
                any(MessageHeaders.class)
        );
    }

    @Test
    void aDroppedSubscriberDoesNotFailTheTurn() {
        doThrow(new IllegalStateException("no session"))
                .when(messagingTemplate)
                .convertAndSendToUser(any(), any(), any(), any(MessageHeaders.class));

        String result = broker.withSession(SESSION_ID, () -> {
            broker.emitThinking(broker.currentSession(), conversationId, "weighing", 1);
            return "turn completed";
        });

        assertThat(result).isEqualTo("turn completed");
    }
}
