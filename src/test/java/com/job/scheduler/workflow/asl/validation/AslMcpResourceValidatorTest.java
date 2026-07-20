package com.job.scheduler.workflow.asl.validation;

import com.job.scheduler.entity.McpServer;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AslMcpResourceValidatorTest {
    @Mock
    private McpServerRepository mcpServerRepository;
    @Mock
    private McpToolRepository mcpToolRepository;

    private AslMcpResourceValidator validator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        validator = new AslMcpResourceValidator(
                mcpServerRepository,
                mcpToolRepository,
                objectMapper
        );
    }

    private List<AslValidationIssue> validate(String resource) {
        JsonNode definition = objectMapper.createObjectNode()
                .put("StartAt", "Call")
                .set("States", objectMapper.createObjectNode()
                        .set("Call", objectMapper.createObjectNode()
                                .put("Type", "Task")
                                .put("Resource", resource)
                                .put("End", true)));
        return validator.validate(definition);
    }

    @Test
    void passesWhenServerAndToolExist() {
        McpServer crm = server("crm");
        when(mcpServerRepository.findByServerId("crm")).thenReturn(Optional.of(crm));
        when(mcpToolRepository.findByMcpServerAndToolName(crm, "get-customer"))
                .thenReturn(Optional.of(new McpTool()));

        assertThat(validate("voyager://mcp/crm/get-customer?trust=WRITE")).isEmpty();
    }

    @Test
    void ignoresNonMcpResources() {
        assertThat(validate("voyager://function/tax@v2")).isEmpty();
        assertThat(validate("voyager://system/webhook")).isEmpty();
    }

    @Test
    void flagsUngrantableTrustLevel() {
        List<AslValidationIssue> issues = validate("voyager://mcp/crm/get?trust=UNTRUSTED");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code()).isEqualTo("MCP_RESOURCE_INVALID"));
    }

    @Test
    void flagsUnknownTrustLevel() {
        List<AslValidationIssue> issues = validate("voyager://mcp/crm/get?trust=bogus");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code()).isEqualTo("MCP_RESOURCE_INVALID"));
    }

    @Test
    void flagsUnregisteredServer() {
        when(mcpServerRepository.findByServerId("crm")).thenReturn(Optional.empty());

        List<AslValidationIssue> issues = validate("voyager://mcp/crm/get-customer");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code()).isEqualTo("MCP_SERVER_NOT_FOUND"));
    }

    @Test
    void flagsUnknownTool() {
        McpServer crm = server("crm");
        when(mcpServerRepository.findByServerId("crm")).thenReturn(Optional.of(crm));
        when(mcpToolRepository.findByMcpServerAndToolName(crm, "missing"))
                .thenReturn(Optional.empty());

        List<AslValidationIssue> issues = validate("voyager://mcp/crm/missing");

        assertThat(issues).singleElement()
                .satisfies(issue -> assertThat(issue.code()).isEqualTo("MCP_TOOL_NOT_FOUND"));
    }

    @Test
    void validatesTaskArgumentKeysAgainstSyncedToolSchema() {
        McpServer crm = server("crm");
        McpTool tool = new McpTool();
        tool.setToolName("get-customer");
        tool.setInputSchema("""
                {
                  "type": "object",
                  "properties": {"customerId": {"type": "string"}},
                  "required": ["customerId"],
                  "additionalProperties": false
                }
                """);
        when(mcpServerRepository.findByServerId("crm")).thenReturn(Optional.of(crm));
        when(mcpToolRepository.findByMcpServerAndToolName(crm, "get-customer"))
                .thenReturn(Optional.of(tool));
        JsonNode definition = objectMapper.readTree("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "voyager://mcp/crm/get-customer",
                      "Arguments": {"payload": "{% $states.input.customerId %}"},
                      "End": true
                    }
                  }
                }
                """);

        assertThat(validator.validate(definition))
                .extracting(AslValidationIssue::code)
                .containsExactlyInAnyOrder("MCP_ARGUMENT_REQUIRED", "MCP_ARGUMENT_UNKNOWN");
    }

    @Test
    void acceptsExactTaskArgumentKeysFromSyncedToolSchema() {
        McpServer crm = server("crm");
        McpTool tool = new McpTool();
        tool.setToolName("get-customer");
        tool.setInputSchema("""
                {"type":"object","properties":{"customerId":{"type":"string"}},
                 "required":["customerId"],"additionalProperties":false}
                """);
        when(mcpServerRepository.findByServerId("crm")).thenReturn(Optional.of(crm));
        when(mcpToolRepository.findByMcpServerAndToolName(crm, "get-customer"))
                .thenReturn(Optional.of(tool));
        JsonNode definition = objectMapper.readTree("""
                {"StartAt":"Call","States":{"Call":{"Type":"Task",
                 "Resource":"voyager://mcp/crm/get-customer",
                 "Arguments":{"customerId":"{% $states.input.customerId %}"},"End":true}}}
                """);

        assertThat(validator.validate(definition)).isEmpty();
    }

    @Test
    void recursesIntoParallelBranches() {
        lenient().when(mcpServerRepository.findByServerId(eq("crm"))).thenReturn(Optional.empty());

        JsonNode definition = objectMapper.readTree("""
                {
                  "StartAt": "Fan",
                  "States": {
                    "Fan": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {
                          "Inner": {
                            "Type": "Task",
                            "Resource": "voyager://mcp/crm/get-customer",
                            "End": true
                          }
                        }
                      }],
                      "End": true
                    }
                  }
                }
                """);

        List<AslValidationIssue> issues = validator.validate(definition);

        assertThat(issues).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.code()).isEqualTo("MCP_SERVER_NOT_FOUND");
                    assertThat(issue.location()).contains("Branches[0]");
                });
    }

    private McpServer server(String serverId) {
        McpServer server = new McpServer();
        server.setServerId(serverId);
        return server;
    }
}
