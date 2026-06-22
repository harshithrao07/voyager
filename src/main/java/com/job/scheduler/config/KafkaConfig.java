package com.job.scheduler.config;

import com.job.scheduler.constants.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafka
public class KafkaConfig {
    @Bean
    public NewTopic workflowTaskQueueTopic() {
        return TopicBuilder.name(Topics.TOPIC_WORKFLOW_TASK_QUEUE)
                .partitions(36)
                .replicas(1)
                .build();
    }
}
