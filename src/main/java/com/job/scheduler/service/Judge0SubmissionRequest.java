package com.job.scheduler.service;

record Judge0SubmissionRequest(
        int languageId,
        String sourceCode,
        String additionalFilesBase64,
        String stdin,
        String compilerOptions,
        String commandLineArguments,
        double cpuTimeLimitSeconds,
        double wallTimeLimitSeconds,
        int memoryLimitKb,
        int maxFileSizeKb,
        boolean enableNetwork
) {
}
