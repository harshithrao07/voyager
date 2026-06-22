package com.job.scheduler.scheduler;

import com.job.scheduler.service.WorkflowSchedulingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DueWorkflowSchedulerServiceTest {
    @Mock
    private WorkflowSchedulingService workflowSchedulingService;

    private DueWorkflowSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DueWorkflowSchedulerService(workflowSchedulingService);
        ReflectionTestUtils.setField(scheduler, "claimLimit", 25);
    }

    @Test
    void materializesDueWorkflowExecutions() {
        scheduler.materializeDueExecutions();

        verify(workflowSchedulingService).materializeDueExecutions(
                any(Instant.class),
                eq(25)
        );
    }
}
