package com.job.scheduler.service;

import com.job.scheduler.storage.ObjectStorageService;
import com.job.scheduler.storage.StorageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowPayloadStorageServiceTest {
    @Mock
    private ObjectStorageService objectStorageService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void storesSmallPayloadInline() {
        WorkflowPayloadStorageService service = service(32, 128);

        var stored = service.store("input.json", "{\"ok\":true}");

        assertThat(stored.inlineValue()).isEqualTo("{\"ok\":true}");
        assertThat(stored.reference()).isNull();
        verify(objectStorageService, never()).put(any(), any(), any());
    }

    @Test
    void storesLargePayloadInObjectStorage() {
        WorkflowPayloadStorageService service = service(8, 128);
        byte[] bytes = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        StorageRef ref = new StorageRef(
                "MINIO",
                "workflow",
                "input.json",
                null,
                "application/json",
                bytes.length,
                "sha256:unused"
        );
        when(objectStorageService.put(eq("input.json"), any(byte[].class), eq("application/json")))
                .thenReturn(ref);

        var stored = service.store("input.json", new String(bytes, StandardCharsets.UTF_8));

        assertThat(stored.inlineValue()).isNull();
        assertThat(stored.reference()).contains("\"provider\":\"MINIO\"");
    }

    @Test
    void rejectsPayloadOverMaximumSize() {
        WorkflowPayloadStorageService service = service(8, 16);

        assertThatThrownBy(() -> service.store("input.json", "{\"message\":\"too large\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the maximum");
    }

    @Test
    void resolvesExternalPayloadAndVerifiesChecksum() throws Exception {
        WorkflowPayloadStorageService service = service(8, 128);
        byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        String checksum = "sha256:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        StorageRef ref = new StorageRef(
                "MINIO",
                "workflow",
                "output.json",
                null,
                "application/json",
                bytes.length,
                checksum
        );
        when(objectStorageService.get(ref)).thenReturn(bytes);

        String resolved = service.resolve(null, objectMapper.writeValueAsString(ref));

        assertThat(resolved).isEqualTo("{\"ok\":true}");
    }

    private WorkflowPayloadStorageService service(long inlineLimit, long maximumSize) {
        return new WorkflowPayloadStorageService(
                objectMapper,
                Optional.of(objectStorageService),
                inlineLimit,
                maximumSize
        );
    }
}
