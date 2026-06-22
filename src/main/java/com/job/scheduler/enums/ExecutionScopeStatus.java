package com.job.scheduler.enums;

public enum ExecutionScopeStatus {
    PENDING,
    RUNNING,
    WAITING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELED,
    TIMED_OUT
}
