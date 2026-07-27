package com.job.scheduler.enums;

public enum AiModelEvaluationMode {
    QUICK(1),
    RELIABILITY(3);

    private final int repetitions;

    AiModelEvaluationMode(int repetitions) {
        this.repetitions = repetitions;
    }

    public int repetitions() {
        return repetitions;
    }
}
