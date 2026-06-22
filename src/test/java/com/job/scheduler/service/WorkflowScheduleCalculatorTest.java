package com.job.scheduler.service;

import com.job.scheduler.entity.Workflow;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowScheduleCalculatorTest {
    private final WorkflowScheduleCalculator calculator =
            new WorkflowScheduleCalculator();

    @Test
    void calculatesNextOccurrenceFromScheduledTime() {
        Workflow workflow = new Workflow();
        workflow.setCronExpression("0 0 * * * *");
        workflow.setTimezone("UTC");

        Instant next = calculator.nextOccurrenceAfter(
                workflow,
                Instant.parse("2026-06-21T10:00:00Z")
        );

        assertThat(next).isEqualTo(
                Instant.parse("2026-06-21T11:00:00Z")
        );
    }

    @Test
    void appliesWorkflowTimezone() {
        Workflow workflow = new Workflow();
        workflow.setCronExpression("0 0 9 * * *");
        workflow.setTimezone("Asia/Kolkata");

        Instant next = calculator.nextOccurrenceAfter(
                workflow,
                Instant.parse("2026-06-21T03:30:00Z")
        );

        assertThat(next).isEqualTo(
                Instant.parse("2026-06-22T03:30:00Z")
        );
    }
}
