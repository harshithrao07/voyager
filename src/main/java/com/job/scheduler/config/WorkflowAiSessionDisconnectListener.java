package com.job.scheduler.config;

import com.job.scheduler.service.WorkflowAiTurnRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Cancels the in-flight workflow-AI turn when its websocket disconnects.
 *
 * <p>The browser cancels a turn by closing its dedicated socket, which fires this event. Because a
 * completed turn is deregistered before its reply is sent, a disconnect after normal completion is a
 * no-op — only a turn still running for the session is cancelled.
 */
@Component
@RequiredArgsConstructor
public class WorkflowAiSessionDisconnectListener {

    private final WorkflowAiTurnRegistry turnRegistry;

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        turnRegistry.cancel(event.getSessionId());
    }
}
