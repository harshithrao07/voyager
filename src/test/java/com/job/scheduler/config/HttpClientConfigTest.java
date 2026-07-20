package com.job.scheduler.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientConfigTest {

    @Test
    void buildsRestClientWithConfiguredTimeouts() {
        RestClient restClient = new HttpClientConfig().restClient(5000L, 10000L);

        assertThat(restClient).isNotNull();
    }
}
