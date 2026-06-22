package com.job.scheduler.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

@Component
public class WorkflowDefinitionCanonicalizer {
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalWorkflowDefinition canonicalize(JsonNode definition) {
        JsonNode canonicalNode = sortRecursively(definition);
        final String canonicalJson;
        try {
            canonicalJson = objectMapper.writeValueAsString(canonicalNode);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not canonicalize ASL definition", exception);
        }

        return new CanonicalWorkflowDefinition(
                canonicalJson,
                sha256(canonicalJson)
        );
    }

    private JsonNode sortRecursively(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            value.properties()
                    .stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .forEach(entry -> sorted.set(
                            entry.getKey(),
                            sortRecursively(entry.getValue())
                    ));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            value.forEach(element -> sorted.add(sortRecursively(element)));
            return sorted;
        }
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CanonicalWorkflowDefinition(
            String json,
            String hash
    ) {
    }
}
