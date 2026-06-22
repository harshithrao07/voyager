package com.job.scheduler.workflow.asl.runtime;

public class WorkflowPayloadLimitExceededException
        extends IllegalArgumentException {
    private final WorkflowPayloadLimits.Kind kind;
    private final long actualBytes;
    private final long maximumBytes;

    public WorkflowPayloadLimitExceededException(
            WorkflowPayloadLimits.Kind kind,
            long actualBytes,
            long maximumBytes
    ) {
        super(kind.label() + " is " + actualBytes
                + " UTF-8 bytes; maximum is " + maximumBytes);
        this.kind = kind;
        this.actualBytes = actualBytes;
        this.maximumBytes = maximumBytes;
    }

    public WorkflowPayloadLimits.Kind kind() {
        return kind;
    }

    public long actualBytes() {
        return actualBytes;
    }

    public long maximumBytes() {
        return maximumBytes;
    }
}
