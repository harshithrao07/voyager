package com.job.scheduler.producers;

import com.job.scheduler.constants.Topics;
import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTaskQueueProducerTest {

    @Test
    void sendsAttemptUsingAttemptIdAsKafkaKey() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<UUID, WorkflowTaskDispatchEvent> kafkaTemplate =
                mock(KafkaTemplate.class);
        UUID attemptId = UUID.randomUUID();
        WorkflowTaskDispatchEvent event =
                new WorkflowTaskDispatchEvent(attemptId);
        CompletableFuture<SendResult<UUID, WorkflowTaskDispatchEvent>> sent =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(
                Topics.TOPIC_WORKFLOW_TASK_QUEUE,
                attemptId,
                event
        )).thenReturn(sent);

        CompletableFuture<Void> result =
                new WorkflowTaskQueueProducer(kafkaTemplate).send(event);

        assertThat(result).succeedsWithin(java.time.Duration.ofSeconds(1));
        verify(kafkaTemplate).send(
                Topics.TOPIC_WORKFLOW_TASK_QUEUE,
                attemptId,
                event
        );
    }

    @Test
    void exposesKafkaSendFailureToCaller() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<UUID, WorkflowTaskDispatchEvent> kafkaTemplate =
                mock(KafkaTemplate.class);
        WorkflowTaskDispatchEvent event =
                new WorkflowTaskDispatchEvent(UUID.randomUUID());
        CompletableFuture<SendResult<UUID, WorkflowTaskDispatchEvent>> failed =
                CompletableFuture.failedFuture(
                        new IllegalStateException("Kafka unavailable")
                );
        when(kafkaTemplate.send(
                Topics.TOPIC_WORKFLOW_TASK_QUEUE,
                event.stateExecutionAttemptId(),
                event
        )).thenReturn(failed);

        CompletableFuture<Void> result =
                new WorkflowTaskQueueProducer(kafkaTemplate).send(event);

        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("Kafka unavailable");
    }
}
