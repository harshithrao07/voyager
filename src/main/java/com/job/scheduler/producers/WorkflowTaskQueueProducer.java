package com.job.scheduler.producers;

import com.job.scheduler.constants.Topics;
import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowTaskQueueProducer {
    private final KafkaTemplate<UUID, WorkflowTaskDispatchEvent> kafkaTemplate;

    public CompletableFuture<Void> send(WorkflowTaskDispatchEvent event) {
        UUID attemptId = event.stateExecutionAttemptId();
        return kafkaTemplate.send(
                        Topics.TOPIC_WORKFLOW_TASK_QUEUE,
                        attemptId,
                        event
                )
                .whenComplete((result, exception) -> {
                    if (exception == null) {
                        log.info(
                                "Workflow task attempt {} sent to Kafka",
                                attemptId
                        );
                    } else {
                        log.error(
                                "Could not send workflow task attempt {}",
                                attemptId,
                                exception
                        );
                    }
                })
                .thenApply(result -> null);
    }
}
