package com.job.scheduler.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Judge0SubmissionResultTest {

    @Test
    void inQueueAndProcessingAreConsideredProcessing() {
        assertThat(result(1).isProcessing()).isTrue();  // In Queue
        assertThat(result(2).isProcessing()).isTrue();  // Processing
    }

    @Test
    void acceptedIsNotProcessing() {
        assertThat(result(3).isProcessing()).isFalse();
    }

    @Test
    void nullStatusIsNeitherProcessingNorAccepted() {
        Judge0SubmissionResult result = result(null);

        assertThat(result.isProcessing()).isFalse();
        assertThat(result.isAccepted()).isFalse();
    }

    @Test
    void onlyStatusThreeIsAccepted() {
        assertThat(result(3).isAccepted()).isTrue();
        assertThat(result(4).isAccepted()).isFalse();
        assertThat(result(1).isAccepted()).isFalse();
    }

    private Judge0SubmissionResult result(Integer statusId) {
        return new Judge0SubmissionResult(
                "token", statusId, "desc",
                null, null, null, null,
                null, null, null, null, null
        );
    }
}
