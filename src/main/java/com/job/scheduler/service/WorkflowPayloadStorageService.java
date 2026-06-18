package com.job.scheduler.service;

import com.job.scheduler.storage.ObjectStorageService;
import com.job.scheduler.storage.StorageRef;
import com.job.scheduler.storage.StoredPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class WorkflowPayloadStorageService {
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;
    private final Optional<ObjectStorageService> objectStorageService;
    private final long inlineLimitBytes;
    private final long maximumPayloadBytes;

    public WorkflowPayloadStorageService(
            ObjectMapper objectMapper,
            Optional<ObjectStorageService> objectStorageService,
            @Value("${scheduler.workflow.inline-payload-limit-bytes:262144}") long inlineLimitBytes,
            @Value("${scheduler.workflow.max-payload-size-bytes:104857600}") long maximumPayloadBytes
    ) {
        if (inlineLimitBytes < 0 || maximumPayloadBytes < inlineLimitBytes) {
            throw new IllegalArgumentException("Workflow payload size limits are invalid");
        }
        this.objectMapper = objectMapper;
        this.objectStorageService = objectStorageService;
        this.inlineLimitBytes = inlineLimitBytes;
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    public StoredPayload store(String key, String json) {
        if (json == null) {
            return new StoredPayload(null, null);
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumPayloadBytes) {
            throw new IllegalArgumentException(
                    "Workflow payload is " + bytes.length
                            + " bytes and exceeds the maximum of " + maximumPayloadBytes + " bytes"
            );
        }
        if (bytes.length <= inlineLimitBytes) {
            return new StoredPayload(json, null);
        }

        ObjectStorageService storage = objectStorageService.orElseThrow(() ->
                new IllegalStateException(
                        "Workflow payload exceeds the inline limit, but object storage is disabled"
                )
        );
        StorageRef ref = storage.put(key, bytes, JSON_CONTENT_TYPE);
        return new StoredPayload(null, writeReference(ref));
    }

    public String resolve(String inlineValue, String reference) {
        if (inlineValue != null && reference != null) {
            throw new IllegalStateException("Workflow payload cannot be both inline and externally stored");
        }
        if (inlineValue != null) {
            return inlineValue;
        }
        if (reference == null) {
            return null;
        }

        StorageRef ref = readReference(reference);
        byte[] bytes = objectStorageService.orElseThrow(() ->
                new IllegalStateException("Object storage is disabled")
        ).get(ref);
        verifyChecksum(ref, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String writeReference(StorageRef ref) {
        try {
            return objectMapper.writeValueAsString(ref);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize storage reference", exception);
        }
    }

    private StorageRef readReference(String reference) {
        try {
            return objectMapper.readValue(reference, StorageRef.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not deserialize storage reference", exception);
        }
    }

    private void verifyChecksum(StorageRef ref, byte[] bytes) {
        String actual = sha256(bytes);
        if (ref.checksum() != null && !ref.checksum().equals(actual)) {
            throw new IllegalStateException("Stored workflow payload checksum does not match");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
