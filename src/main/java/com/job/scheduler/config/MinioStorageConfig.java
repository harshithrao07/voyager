package com.job.scheduler.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "scheduler.storage.enabled", havingValue = "true")
    MinioClient minioClient(
            @Value("${scheduler.storage.endpoint}") String endpoint,
            @Value("${scheduler.storage.access-key}") String accessKey,
            @Value("${scheduler.storage.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
