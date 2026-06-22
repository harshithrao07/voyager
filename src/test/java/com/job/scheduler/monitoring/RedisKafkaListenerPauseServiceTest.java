package com.job.scheduler.monitoring;

import com.job.scheduler.service.RedisHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisKafkaListenerPauseServiceTest {

    @Test
    void pausesRunningWorkersWhenRedisIsUnavailable() {
        RedisHealthService redisHealthService =
                mock(RedisHealthService.class);
        KafkaListenerEndpointRegistry registry =
                mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer running = container(true, false);
        MessageListenerContainer stopped = container(false, false);
        when(redisHealthService.isRedisAvailable()).thenReturn(false);
        when(registry.getListenerContainers())
                .thenReturn(List.of(running, stopped));

        new RedisKafkaListenerPauseService(
                redisHealthService,
                registry
        ).pauseWorkersWhenRedisIsDown();

        verify(running).pause();
        verify(stopped, never()).pause();
    }

    @Test
    void resumesPausedWorkersWhenRedisRecovers() {
        RedisHealthService redisHealthService =
                mock(RedisHealthService.class);
        KafkaListenerEndpointRegistry registry =
                mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer paused = container(true, true);
        MessageListenerContainer active = container(true, false);
        when(redisHealthService.isRedisAvailable()).thenReturn(true);
        when(registry.getListenerContainers())
                .thenReturn(List.of(paused, active));

        new RedisKafkaListenerPauseService(
                redisHealthService,
                registry
        ).pauseWorkersWhenRedisIsDown();

        verify(paused).resume();
        verify(active, never()).resume();
    }

    @Test
    void leavesAlreadyPausedWorkerPausedDuringOutage() {
        RedisHealthService redisHealthService =
                mock(RedisHealthService.class);
        KafkaListenerEndpointRegistry registry =
                mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer paused = container(true, true);
        when(redisHealthService.isRedisAvailable()).thenReturn(false);
        when(registry.getListenerContainers()).thenReturn(List.of(paused));

        new RedisKafkaListenerPauseService(
                redisHealthService,
                registry
        ).pauseWorkersWhenRedisIsDown();

        verify(paused, never()).pause();
        verify(paused, never()).resume();
    }

    private MessageListenerContainer container(
            boolean running,
            boolean paused
    ) {
        MessageListenerContainer container =
                mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(running);
        when(container.isContainerPaused()).thenReturn(paused);
        when(container.getListenerId()).thenReturn("worker");
        return container;
    }
}
