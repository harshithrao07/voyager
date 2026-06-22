package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslRuntimeCapabilityValidatorTest {
    private ObjectMapper objectMapper;
    private AslRuntimeCapabilityValidator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validator = new AslRuntimeCapabilityValidator();
    }

    @Test
    void acceptsOnlyCurrentlyImplementedInlineStates() {
        var issues = validator.validate(objectMapper.readTree("""
                {
                  "StartAt": "Route",
                  "States": {
                    "Route": {
                      "Type": "Choice",
                      "Choices": [
                        {
                          "Condition": "{% true %}",
                          "Next": "Done"
                        }
                      ]
                    },
                    "Done": {
                      "Type": "Succeed"
                    }
                  }
                }
                """));

        assertThat(issues).isEmpty();
    }

    @Test
    void acceptsTaskNowThatKafkaRuntimeExists() {
        var issues = validator.validate(objectMapper.readTree("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "scheduler://cleanup",
                      "End": true
                    }
                  }
                }
                """));

        assertThat(issues).isEmpty();
    }
}
