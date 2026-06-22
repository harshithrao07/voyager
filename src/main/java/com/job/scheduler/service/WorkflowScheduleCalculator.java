package com.job.scheduler.service;

import com.job.scheduler.entity.Workflow;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class WorkflowScheduleCalculator {

    public Instant nextOccurrenceAfter(
            Workflow workflow,
            Instant occurrence
    ) {
        if (workflow.getCronExpression() == null) {
            return null;
        }
        ZoneId zone = ZoneId.of(workflow.getTimezone());
        ZonedDateTime next = CronExpression
                .parse(workflow.getCronExpression())
                .next(ZonedDateTime.ofInstant(occurrence, zone));
        return next == null ? null : next.toInstant();
    }
}
