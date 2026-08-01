package com.job.scheduler.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the pure retrieval-metric math behind the embedding ranking. */
class EmbeddingRankingServiceTest {

    @Test
    void cosineIsOneForIdenticalAndZeroForOrthogonal() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        float[] c = {2f, 0f, 0f}; // same direction as a, different magnitude

        assertThat(EmbeddingRankingService.cosine(a, a)).isCloseTo(1.0, within(1e-6));
        assertThat(EmbeddingRankingService.cosine(a, b)).isCloseTo(0.0, within(1e-6));
        assertThat(EmbeddingRankingService.cosine(a, c)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void rankOfPutsMostSimilarResourceFirst() {
        // Query points along axis 0; resource 0 is identical, resource 2 orthogonal, resource 1 close.
        float[] query = {1f, 0f, 0f};
        List<float[]> resources = List.of(
                new float[] {1f, 0f, 0f},      // index 0 — identical → rank 1
                new float[] {0.9f, 0.1f, 0f},  // index 1 — near
                new float[] {0f, 1f, 0f}       // index 2 — orthogonal → last
        );

        assertThat(EmbeddingRankingService.rankOf(query, resources, 0)).isEqualTo(1);
        assertThat(EmbeddingRankingService.rankOf(query, resources, 1)).isEqualTo(2);
        assertThat(EmbeddingRankingService.rankOf(query, resources, 2)).isEqualTo(3);
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
