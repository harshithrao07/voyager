package com.job.scheduler.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDefinitionCanonicalizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowDefinitionCanonicalizer canonicalizer =
            new WorkflowDefinitionCanonicalizer(objectMapper);

    @Test
    void equivalentObjectFieldOrderProducesSameCanonicalJsonAndHash() {
        var first = canonicalizer.canonicalize(objectMapper.readTree("""
                {
                  "StartAt": "Done",
                  "States": {
                    "Done": {
                      "Comment": "complete",
                      "Type": "Succeed"
                    }
                  }
                }
                """));
        var second = canonicalizer.canonicalize(objectMapper.readTree("""
                {
                  "States": {
                    "Done": {
                      "Type": "Succeed",
                      "Comment": "complete"
                    }
                  },
                  "StartAt": "Done"
                }
                """));

        assertThat(first.json()).isEqualTo(second.json());
        assertThat(first.hash()).isEqualTo(second.hash()).hasSize(64);
    }

    @Test
    void arrayOrderRemainsSignificant() {
        var first = canonicalizer.canonicalize(objectMapper.readTree("[1,2]"));
        var second = canonicalizer.canonicalize(objectMapper.readTree("[2,1]"));

        assertThat(first.hash()).isNotEqualTo(second.hash());
    }
}
