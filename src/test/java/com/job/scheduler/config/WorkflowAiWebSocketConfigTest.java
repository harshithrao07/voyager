package com.job.scheduler.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAiWebSocketConfigTest {
    private final WorkflowAiWebSocketConfig config = new WorkflowAiWebSocketConfig();

    @Test
    void configuresSimpleBrokerWithHeartbeatsAndPrefixes() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration brokerRegistration = mock(SimpleBrokerRegistration.class);
        when(registry.enableSimpleBroker("/queue", "/topic")).thenReturn(brokerRegistration);
        when(brokerRegistration.setHeartbeatValue(any())).thenReturn(brokerRegistration);
        when(brokerRegistration.setTaskScheduler(any())).thenReturn(brokerRegistration);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/queue", "/topic");
        // Heart-beats let the browser prove liveness without a duration-coupled timeout.
        verify(brokerRegistration).setHeartbeatValue(new long[]{10_000, 10_000});
        verify(brokerRegistration).setTaskScheduler(any(TaskScheduler.class));
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry).setUserDestinationPrefix("/user");
    }

    @Test
    void suppliesAnInitializedHeartbeatScheduler() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration brokerRegistration = mock(SimpleBrokerRegistration.class);
        when(registry.enableSimpleBroker(any(String[].class))).thenReturn(brokerRegistration);
        when(brokerRegistration.setHeartbeatValue(any())).thenReturn(brokerRegistration);
        when(brokerRegistration.setTaskScheduler(any())).thenReturn(brokerRegistration);

        config.configureMessageBroker(registry);

        org.mockito.ArgumentCaptor<TaskScheduler> scheduler =
                org.mockito.ArgumentCaptor.forClass(TaskScheduler.class);
        verify(brokerRegistration).setTaskScheduler(scheduler.capture());
        assertThat(scheduler.getValue()).isNotNull();
        // Must already be initialized, or scheduling a heart-beat would throw IllegalStateException.
        scheduler.getValue().schedule(() -> { }, java.time.Instant.now());
    }

    @Test
    void registersStompEndpointWithOpenOrigins() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws")).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
        verify(registration).setAllowedOriginPatterns("*");
    }
}
