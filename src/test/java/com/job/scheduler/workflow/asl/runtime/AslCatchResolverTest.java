package com.job.scheduler.workflow.asl.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AslCatchResolverTest {
    private ObjectMapper objectMapper;
    private AslCatchResolver resolver;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        resolver = new AslCatchResolver(new AslErrorMatcher());
    }

    private JsonNode tree(String json) {
        return objectMapper.readTree(json);
    }

    @Test
    void returnsNullWhenCatchersIsNull() {
        assertThat(resolver.resolve(null, "States.TaskFailed")).isNull();
    }

    @Test
    void returnsNullWhenCatchersIsNotArray() {
        assertThat(resolver.resolve(tree("{\"ErrorEquals\":[\"States.ALL\"]}"), "X"))
                .isNull();
    }

    @Test
    void returnsMatchingCatcher() {
        JsonNode catchers = tree("""
                [
                  {"ErrorEquals": ["Payment.Declined"], "Next": "HandleDecline"}
                ]
                """);

        JsonNode resolved = resolver.resolve(catchers, "Payment.Declined");

        assertThat(resolved).isNotNull();
        assertThat(resolved.get("Next").stringValue()).isEqualTo("HandleDecline");
    }

    @Test
    void returnsFirstCatcherThatMatchesInDeclarationOrder() {
        JsonNode catchers = tree("""
                [
                  {"ErrorEquals": ["Payment.Declined"], "Next": "Specific"},
                  {"ErrorEquals": ["States.ALL"], "Next": "Catchall"}
                ]
                """);

        JsonNode resolved = resolver.resolve(catchers, "Other.Error");

        assertThat(resolved.get("Next").stringValue()).isEqualTo("Catchall");
    }

    @Test
    void prefersEarlierCatcherWhenMultipleMatch() {
        JsonNode catchers = tree("""
                [
                  {"ErrorEquals": ["States.ALL"], "Next": "First"},
                  {"ErrorEquals": ["States.ALL"], "Next": "Second"}
                ]
                """);

        JsonNode resolved = resolver.resolve(catchers, "Anything");

        assertThat(resolved.get("Next").stringValue()).isEqualTo("First");
    }

    @Test
    void returnsNullWhenNoCatcherMatches() {
        JsonNode catchers = tree("""
                [
                  {"ErrorEquals": ["Payment.Declined"], "Next": "HandleDecline"}
                ]
                """);

        assertThat(resolver.resolve(catchers, "Network.Error")).isNull();
    }
}
