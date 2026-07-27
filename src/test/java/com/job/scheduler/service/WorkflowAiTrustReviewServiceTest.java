package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowAiTrustReviewDTO;
import com.job.scheduler.entity.McpServer;
import com.job.scheduler.enums.McpTrustLevel;
import com.job.scheduler.repository.McpServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAiTrustReviewServiceTest {

    @Mock
    private McpServerRepository mcpServerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowAiTrustReviewService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowAiTrustReviewService(mcpServerRepository);
        lenient().when(mcpServerRepository.findByServerId(anyString())).thenReturn(Optional.empty());
    }

    private JsonNode asl(String json) throws JacksonException {
        return objectMapper.readTree(json);
    }

    @Test
    void flagsWriteTrustGrant() throws Exception {
        McpServer server = new McpServer();
        server.setServerId("crm");
        server.setDisplayName("CRM");
        server.setTrustLevel(McpTrustLevel.WRITE);
        when(mcpServerRepository.findByServerId("crm")).thenReturn(Optional.of(server));

        WorkflowAiTrustReviewDTO review = service.review(asl("""
                {"StartAt":"Create","States":{
                  "Create":{"Type":"Task","Resource":"voyager://mcp/crm/create-lead?trust=WRITE","End":true}
                }}
                """));

        assertThat(review.requiresConfirmation()).isTrue();
        assertThat(review.tools()).hasSize(1);
        assertThat(review.tools().get(0).stateName()).isEqualTo("Create");
        assertThat(review.tools().get(0).serverId()).isEqualTo("crm");
        assertThat(review.tools().get(0).toolName()).isEqualTo("create-lead");
        assertThat(review.tools().get(0).grantedTrustLevel()).isEqualTo(McpTrustLevel.WRITE);
        assertThat(review.tools().get(0).serverDisplayName()).isEqualTo("CRM");
        assertThat(review.tools().get(0).serverTrustLevel()).isEqualTo(McpTrustLevel.WRITE);
    }

    @Test
    void flagsDestructiveTrustGrant() throws Exception {
        WorkflowAiTrustReviewDTO review = service.review(asl("""
                {"StartAt":"Wipe","States":{
                  "Wipe":{"Type":"Task","Resource":"voyager://mcp/db/drop-table?trust=DESTRUCTIVE","End":true}
                }}
                """));

        assertThat(review.requiresConfirmation()).isTrue();
        assertThat(review.tools().get(0).grantedTrustLevel()).isEqualTo(McpTrustLevel.DESTRUCTIVE);
        // Unregistered server -> context fields are null but the grant is still flagged.
        assertThat(review.tools().get(0).serverDisplayName()).isNull();
    }

    @Test
    void ignoresReadOnlyAndDefaultTrust() throws Exception {
        WorkflowAiTrustReviewDTO review = service.review(asl("""
                {"StartAt":"Read","States":{
                  "Read":{"Type":"Task","Resource":"voyager://mcp/crm/get-lead?trust=READ_ONLY","Next":"Default"},
                  "Default":{"Type":"Task","Resource":"voyager://mcp/crm/list-leads","End":true}
                }}
                """));

        assertThat(review.requiresConfirmation()).isFalse();
        assertThat(review.tools()).isEmpty();
    }

    @Test
    void ignoresNonMcpResources() throws Exception {
        WorkflowAiTrustReviewDTO review = service.review(asl("""
                {"StartAt":"Fn","States":{
                  "Fn":{"Type":"Task","Resource":"voyager://function/do-thing","End":true}
                }}
                """));

        assertThat(review.requiresConfirmation()).isFalse();
    }

    @Test
    void findsElevatedToolsNestedInParallelAndMap() throws Exception {
        WorkflowAiTrustReviewDTO review = service.review(asl("""
                {"StartAt":"Fan","States":{
                  "Fan":{"Type":"Parallel","Branches":[
                    {"StartAt":"B1","States":{
                      "B1":{"Type":"Task","Resource":"voyager://mcp/crm/create-lead?trust=WRITE","End":true}}}
                  ],"Next":"Loop"},
                  "Loop":{"Type":"Map","ItemProcessor":{"StartAt":"M1","States":{
                    "M1":{"Type":"Task","Resource":"voyager://mcp/db/delete-row?trust=DESTRUCTIVE","End":true}}},"End":true}
                }}
                """));

        assertThat(review.tools()).hasSize(2);
        assertThat(review.tools()).extracting(t -> t.stateName())
                .containsExactlyInAnyOrder("B1", "M1");
    }

    @Test
    void toleratesMalformedMcpResource() throws Exception {
        WorkflowAiTrustReviewDTO review = service.review(asl("""
                {"StartAt":"Bad","States":{
                  "Bad":{"Type":"Task","Resource":"voyager://mcp/only-server","End":true}
                }}
                """));

        assertThat(review.requiresConfirmation()).isFalse();
    }

    @Test
    void handlesNullDefinition() {
        assertThat(service.review(null).requiresConfirmation()).isFalse();
    }
}
