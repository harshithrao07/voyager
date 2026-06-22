package com.job.scheduler.consumers;

import com.job.scheduler.dto.WorkflowTaskDispatchEvent;
import com.job.scheduler.service.WorkflowTaskWorkerService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowTaskQueueConsumerTest {

    @Test
    void delegatesKafkaEventToWorkerService() {
        WorkflowTaskWorkerService workerService =
                mock(WorkflowTaskWorkerService.class);
        WorkflowTaskDispatchEvent event =
                new WorkflowTaskDispatchEvent(UUID.randomUUID());

        new WorkflowTaskQueueConsumer(workerService).consume(event);

        verify(workerService).process(event);
    }
}
