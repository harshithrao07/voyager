package com.job.scheduler.handlers;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.McpToolPayload;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.service.McpToolExecutionService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolHandlerTest {

    @Mock
    private McpToolExecutionService mcpToolExecutionService;

    private McpToolHandler mcpToolHandler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mcpToolHandler = new McpToolHandler(mcpToolExecutionService, objectMapper);
    }

    @Test
    void handleReturnsStructuredMcpResult() {
        ObjectNode arguments = objectMapper.createObjectNode().put("message", "hello");
        McpToolPayload payload = new McpToolPayload(
                "local-tools",
                "ping",
                arguments,
                McpTrustLevel.READ_ONLY
        );
        McpSchema.CallToolResult callResult = new McpSchema.CallToolResult(
                List.of(),
                false,
                Map.of("ok", true),
                Map.of()
        );
        when(mcpToolExecutionService.callTool(
                "local-tools",
                "ping",
                Map.of("message", "hello"),
                McpTrustLevel.READ_ONLY
        )).thenReturn(Mono.just(callResult));

        StepResult result = mcpToolHandler.handle(payload);

        assertThat(result.output().get("structuredContent").get("ok").booleanValue()).isTrue();
        verify(mcpToolExecutionService).callTool(
                "local-tools",
                "ping",
                Map.of("message", "hello"),
                McpTrustLevel.READ_ONLY
        );
    }
}
