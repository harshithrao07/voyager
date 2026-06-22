package com.job.scheduler.consumers;

import com.job.scheduler.constants.Topics;
import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import com.job.scheduler.service.WorkflowTaskWorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowTaskQueueConsumer {
    private final WorkflowTaskWorkerService workerService;

    @KafkaListener(
            id = "workflow-task-queue-worker",
            topics = Topics.TOPIC_WORKFLOW_TASK_QUEUE,
            groupId = "scheduler-workflow-task-group",
            concurrency = "12",
            properties = {
                    "spring.json.value.default.type="
                            + "com.job.scheduler.dto.WorkflowTaskDispatchEvent"
            }
    )
    public void consume(WorkflowTaskDispatchEvent event) {
        workerService.process(event);
    }
}
