package com.job.scheduler.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaErrorHandlerConfigTest {

    @Test
    void buildsDefaultErrorHandler() {
        CommonErrorHandler handler = new KafkaErrorHandlerConfig().kafkaErrorHandler(5000L);

        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
    }
}
