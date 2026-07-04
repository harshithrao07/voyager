package com.job.scheduler.service;

record Judge0SubmissionResult(
        String token,
        Integer statusId,
        String statusDescription,
        String stdout,
        String stderr,
        String compileOutput,
        String message,
        Integer exitCode,
        Integer exitSignal,
        Double timeSeconds,
        Double wallTimeSeconds,
        Long memoryKb
) {
    boolean isProcessing() {
        return statusId != null && (statusId == 1 || statusId == 2);
    }

    boolean isAccepted() {
        return statusId != null && statusId == 3;
    }
}
