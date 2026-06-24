package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslErrorMatcherTest {
    private ObjectMapper objectMapper;
    private AslErrorMatcher matcher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        matcher = new AslErrorMatcher();
    }

    private JsonNode array(String json) {
        return objectMapper.readTree(json);
    }

    @Test
    void returnsFalseWhenErrorEqualsIsNull() {
        assertThat(matcher.matches(null, "States.TaskFailed")).isFalse();
    }

    @Test
    void returnsFalseWhenErrorEqualsIsNotArray() {
        assertThat(matcher.matches(array("\"States.ALL\""), "Whatever")).isFalse();
    }

    @Test
    void returnsFalseWhenErrorIsNull() {
        assertThat(matcher.matches(array("[\"States.ALL\"]"), null)).isFalse();
    }

    @Test
    void matchesExactErrorName() {
        assertThat(matcher.matches(array("[\"Payment.Declined\"]"), "Payment.Declined"))
                .isTrue();
    }

    @Test
    void doesNotMatchDifferentErrorName() {
        assertThat(matcher.matches(array("[\"Payment.Declined\"]"), "Payment.Timeout"))
                .isFalse();
    }

    @Test
    void statesAllMatchesAnyError() {
        assertThat(matcher.matches(array("[\"States.ALL\"]"), "States.Timeout")).isTrue();
        assertThat(matcher.matches(array("[\"States.ALL\"]"), "Anything")).isTrue();
    }

    @Test
    void statesTaskFailedMatchesAnyErrorExceptTimeout() {
        assertThat(matcher.matches(array("[\"States.TaskFailed\"]"), "Custom.Error"))
                .isTrue();
        assertThat(matcher.matches(array("[\"States.TaskFailed\"]"), "States.Permissions"))
                .isTrue();
    }

    @Test
    void statesTaskFailedDoesNotMatchTimeout() {
        assertThat(matcher.matches(array("[\"States.TaskFailed\"]"), "States.Timeout"))
                .isFalse();
    }

    @Test
    void matchesWhenAnyCandidateInArrayMatches() {
        assertThat(matcher.matches(
                array("[\"Foo.Error\", \"Bar.Error\", \"States.Timeout\"]"),
                "States.Timeout"
        )).isTrue();
    }
}
