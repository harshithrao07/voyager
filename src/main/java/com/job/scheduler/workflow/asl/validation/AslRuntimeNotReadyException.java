package com.job.scheduler.workflow.asl.validation;

public class AslRuntimeNotReadyException extends IllegalStateException {
    public AslRuntimeNotReadyException() {
        super("ASL definition is valid, but ASL workflow execution is not implemented yet");
    }
}
