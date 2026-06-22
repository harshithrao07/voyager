package com.job.scheduler.handlers;

import com.job.scheduler.dto.StepResult;

public interface TaskHandler<T> {
    StepResult handle(T payload);
}
