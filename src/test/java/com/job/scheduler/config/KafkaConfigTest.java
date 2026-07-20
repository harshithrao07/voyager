package com.job.scheduler.config;

import com.job.scheduler.constants.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    @Test
    void declaresWorkflowTaskQueueTopic() {
        NewTopic topic = new KafkaConfig().workflowTaskQueueTopic();

        assertThat(topic.name()).isEqualTo(Topics.TOPIC_WORKFLOW_TASK_QUEUE);
        assertThat(topic.numPartitions()).isEqualTo(36);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}
