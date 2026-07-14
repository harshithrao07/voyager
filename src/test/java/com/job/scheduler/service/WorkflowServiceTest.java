package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.CreateWorkflowRevisionRequestDTO;
import com.job.scheduler.dto.UpdateWorkflowMetadataRequestDTO;
import com.job.scheduler.dto.UpdateWorkflowCanvasLayoutRequestDTO;
import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import com.job.scheduler.enums.WorkflowStatus;
import com.job.scheduler.repository.McpServerRepository;
import com.job.scheduler.repository.McpToolRepository;
import com.job.scheduler.repository.WorkflowDefinitionRepository;
import com.job.scheduler.repository.WorkflowRepository;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidationException;
import com.job.scheduler.workflow.asl.validation.AslMcpResourceValidator;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationCategory;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {
    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowDefinitionRepository workflowDefinitionRepository;

    @Mock
    private AslDefinitionValidator aslDefinitionValidator;

    @Mock
    private McpServerRepository mcpServerRepository;

    @Mock
    private McpToolRepository mcpToolRepository;

    private ObjectMapper objectMapper;
    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        workflowService = new WorkflowService(
                workflowRepository,
                workflowDefinitionRepository,
                aslDefinitionValidator,
                new WorkflowDefinitionCanonicalizer(objectMapper),
                objectMapper,
                new AslRuntimeCapabilityValidator(),
                new AslMcpResourceValidator(mcpServerRepository, mcpToolRepository)
        );
    }

    @Test
    void createsInactiveRecurringWorkflowWithImmutableRevisionOne() {
        var definitionNode = definition("Done");
        var request = new CreateWorkflowRequestDTO(
                "Daily cleanup",
                "0 0 9 * * MON-FRI",
                4,
                "workflow-create-1",
                "Asia/Kolkata",
                definitionNode
        );
        when(workflowRepository.findByIdempotencyKey("workflow-create-1"))
                .thenReturn(Optional.empty());
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow workflow = invocation.getArgument(0);
            if (workflow.getId() == null) {
                workflow.setId(UUID.randomUUID());
                workflow.setCreatedAt(Instant.now());
                workflow.setUpdatedAt(Instant.now());
            }
            return workflow;
        });
        when(workflowDefinitionRepository.save(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> {
                    WorkflowDefinition definition = invocation.getArgument(0);
                    definition.setId(UUID.randomUUID());
                    definition.setCreatedAt(Instant.now());
                    return definition;
                });

        var response = workflowService.createWorkflow(request);

        assertThat(response.status()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(response.activeDefinition()).isNull();
        assertThat(response.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(response.nextRunAt()).isNull();
        verify(aslDefinitionValidator, never()).validate(any());

        ArgumentCaptor<WorkflowDefinition> definitionCaptor =
                ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(workflowDefinitionRepository).save(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getRevision()).isEqualTo(1);
        assertThat(definitionCaptor.getValue().getDefinition())
                .isEqualTo("{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}");
    }

    @Test
    void validatesAndActivatesManualWorkflowOnSave() {
        var definitionNode = definition("Done");
        var request = request(definitionNode);
        when(workflowRepository.findByIdempotencyKey("workflow-key"))
                .thenReturn(Optional.empty());
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
                saved.setCreatedAt(Instant.now());
                saved.setUpdatedAt(Instant.now());
            }
            return saved;
        });
        when(workflowDefinitionRepository.save(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> {
                    WorkflowDefinition saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    saved.setCreatedAt(Instant.now());
                    return saved;
                });
        when(aslDefinitionValidator.validate(any()))
                .thenReturn(new AslValidationResult(List.of()));

        var response = workflowService.createWorkflow(request);

        assertThat(response.status()).isEqualTo(WorkflowStatus.ACTIVE);
        assertThat(response.activeDefinition().revision()).isEqualTo(1);
        assertThat(response.activeDefinition().active()).isTrue();
        assertThat(response.nextRunAt()).isNull();
        verify(aslDefinitionValidator).validate(any());
        verify(workflowDefinitionRepository).save(any());
    }

    @Test
    void rejectsManualWorkflowThatIsNotExecutable() {
        var request = request(definition("Done"));
        when(workflowRepository.findByIdempotencyKey("workflow-key"))
                .thenReturn(Optional.empty());
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(workflowDefinitionRepository.save(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(aslDefinitionValidator.validate(any()))
                .thenReturn(new AslValidationResult(List.of(new AslValidationIssue(
                        "$.States.Done",
                        AslValidationCategory.ASL,
                        "TEST_INVALID",
                        "Definition is not executable"
                ))));

        assertThatThrownBy(() -> workflowService.createWorkflow(request))
                .isInstanceOf(AslDefinitionValidationException.class);
    }

    @Test
    void createsNextImmutableRevisionWithoutActivatingIt() {
        Workflow workflow = workflow();
        workflow.setCronExpression("0 0 * * * *");
        WorkflowDefinition current = persistedDefinition(workflow, 1, definition("Done"));
        workflow.setActiveDefinition(current);
        var updatedDefinition = definition("Updated");
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndDefinitionHash(
                any(Workflow.class),
                any(String.class)
        )).thenReturn(Optional.empty());
        when(workflowDefinitionRepository.findFirstByWorkflowOrderByRevisionDesc(workflow))
                .thenReturn(Optional.of(current));
        when(workflowDefinitionRepository.save(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> {
                    WorkflowDefinition definition = invocation.getArgument(0);
                    definition.setId(UUID.randomUUID());
                    definition.setCreatedAt(Instant.now());
                    return definition;
                });

        var response = workflowService.createRevision(
                workflow.getId(),
                new CreateWorkflowRevisionRequestDTO(updatedDefinition, false)
        );

        assertThat(response.revision()).isEqualTo(2);
        assertThat(response.active()).isFalse();
        assertThat(workflow.getActiveDefinition()).isSameAs(current);
        // A non-activating revision is a draft — it is not validated.
        verify(aslDefinitionValidator, never()).validate(any());
    }

    @Test
    void validatesAndActivatesManualRevisionOnSave() {
        Workflow workflow = workflow();
        WorkflowDefinition current = persistedDefinition(workflow, 1, definition("Done"));
        workflow.setActiveDefinition(current);
        workflow.setStatus(WorkflowStatus.ACTIVE);
        var updatedDefinition = definition("Updated");
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndDefinitionHash(
                any(Workflow.class),
                any(String.class)
        )).thenReturn(Optional.empty());
        when(workflowDefinitionRepository.findFirstByWorkflowOrderByRevisionDesc(workflow))
                .thenReturn(Optional.of(current));
        when(workflowDefinitionRepository.save(any(WorkflowDefinition.class)))
                .thenAnswer(invocation -> {
                    WorkflowDefinition definition = invocation.getArgument(0);
                    definition.setId(UUID.randomUUID());
                    definition.setCreatedAt(Instant.now());
                    return definition;
                });
        when(aslDefinitionValidator.validate(any()))
                .thenReturn(new AslValidationResult(List.of()));

        var response = workflowService.createRevision(
                workflow.getId(),
                new CreateWorkflowRevisionRequestDTO(updatedDefinition, false)
        );

        assertThat(response.revision()).isEqualTo(2);
        assertThat(response.active()).isTrue();
        assertThat(workflow.getActiveDefinition().getRevision()).isEqualTo(2);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
        verify(aslDefinitionValidator).validate(any());
    }

    @Test
    void duplicateRevisionReturnsExistingDefinition() {
        Workflow workflow = workflow();
        workflow.setCronExpression("0 0 * * * *");
        WorkflowDefinition current = persistedDefinition(workflow, 1, definition("Done"));
        workflow.setActiveDefinition(current);
        var submitted = definition("Done");
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndDefinitionHash(
                any(Workflow.class),
                any(String.class)
        )).thenReturn(Optional.of(current));

        var response = workflowService.createRevision(
                workflow.getId(),
                new CreateWorkflowRevisionRequestDTO(submitted, false)
        );

        assertThat(response.revision()).isEqualTo(1);
        verify(workflowDefinitionRepository, never()).save(any());
    }

    @Test
    void activatesRevisionSupportedByCurrentInterpreter() {
        Workflow workflow = workflow();
        WorkflowDefinition definition = persistedDefinition(workflow, 1, definition("Done"));
        workflow.setActiveDefinition(definition);
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndRevision(workflow, 1))
                .thenReturn(Optional.of(definition));
        when(aslDefinitionValidator.validate(any()))
                .thenReturn(new AslValidationResult(List.of()));

        var response = workflowService.activateRevision(workflow.getId(), 1);

        assertThat(response.active()).isTrue();
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
        assertThat(workflow.getActiveDefinition()).isSameAs(definition);
        verify(workflowRepository).save(workflow);
    }

    @Test
    void persistsCanvasLayoutWithoutChangingTheAslRevision() {
        Workflow workflow = workflow();
        WorkflowDefinition definition = persistedDefinition(workflow, 1, definition("Done"));
        workflow.setActiveDefinition(definition);
        var positions = objectMapper.createObjectNode()
                .set("Done", objectMapper.createObjectNode()
                        .put("x", 145.5)
                        .put("y", -32));
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndRevision(workflow, 1))
                .thenReturn(Optional.of(definition));
        when(workflowDefinitionRepository.saveAndFlush(definition))
                .thenReturn(definition);

        var response = workflowService.updateCanvasLayout(
                workflow.getId(),
                1,
                new UpdateWorkflowCanvasLayoutRequestDTO(positions)
        );

        assertThat(response.canvasLayout().path("Done").path("x").doubleValue())
                .isEqualTo(145.5);
        assertThat(response.canvasLayout().path("Done").path("y").doubleValue())
                .isEqualTo(-32.0);
        assertThat(response.definition()).isEqualTo(definition("Done"));
        assertThat(response.definitionHash()).isEqualTo(definition.getDefinitionHash());
    }

    @Test
    void persistsScopedCanvasLayoutForParallelAndMapNestedStates() throws Exception {
        Workflow workflow = workflow();
        var nestedDefinition = objectMapper.readTree("""
                {
                  "StartAt": "FanOut",
                  "States": {
                    "FanOut": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "ProcessItems",
                        "States": {
                          "ProcessItems": {
                            "Type": "Map",
                            "Items": [],
                            "ItemProcessor": {
                              "StartAt": "HandleItem",
                              "States": {"HandleItem": {"Type": "Pass", "End": true}}
                            },
                            "End": true
                          }
                        }
                      }],
                      "End": true
                    }
                  }
                }
                """);
        WorkflowDefinition definition = persistedDefinition(workflow, 1, nestedDefinition);
        workflow.setActiveDefinition(definition);
        var positions = objectMapper.createObjectNode();
        positions.set("FanOut", objectMapper.createObjectNode().put("x", 10).put("y", 20));
        positions.set(
                "@scope/States/FanOut/Branches/0/States/ProcessItems",
                objectMapper.createObjectNode().put("x", 30).put("y", 40)
        );
        positions.set(
                "@scope/States/FanOut/Branches/0/States/ProcessItems/ItemProcessor/States/HandleItem",
                objectMapper.createObjectNode().put("x", 50).put("y", 60)
        );
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndRevision(workflow, 1))
                .thenReturn(Optional.of(definition));
        when(workflowDefinitionRepository.saveAndFlush(definition))
                .thenReturn(definition);

        var response = workflowService.updateCanvasLayout(
                workflow.getId(),
                1,
                new UpdateWorkflowCanvasLayoutRequestDTO(positions)
        );

        assertThat(response.canvasLayout()).hasSize(3);
        assertThat(response.canvasLayout()
                .path("@scope/States/FanOut/Branches/0/States/ProcessItems")
                .path("x").doubleValue()).isEqualTo(30.0);
        assertThat(response.canvasLayout()
                .path("@scope/States/FanOut/Branches/0/States/ProcessItems/ItemProcessor/States/HandleItem")
                .path("y").doubleValue()).isEqualTo(60.0);
    }

    @Test
    void activatesDefinitionContainingSupportedTask() {
        Workflow workflow = workflow();
        var taskDefinition = objectMapper.readTree("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "voyager://cleanup",
                      "End": true
                    }
                  }
                }
                """);
        WorkflowDefinition definition =
                persistedDefinition(workflow, 1, taskDefinition);
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndRevision(workflow, 1))
                .thenReturn(Optional.of(definition));
        when(aslDefinitionValidator.validate(any()))
                .thenReturn(new AslValidationResult(List.of()));

        var response = workflowService.activateRevision(workflow.getId(), 1);

        assertThat(response.active()).isTrue();
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
        verify(workflowRepository).save(workflow);
    }

    @Test
    void pausesActiveWorkflowAndClearsNextRun() {
        Workflow workflow = workflow();
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setNextRunAt(Instant.now().plusSeconds(60));
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));

        var response = workflowService.pauseWorkflow(workflow.getId());

        assertThat(response.status()).isEqualTo(WorkflowStatus.PAUSED);
        assertThat(response.nextRunAt()).isNull();
        verify(workflowRepository).save(workflow);
    }

    @Test
    void resumesPausedWorkflowAndRecalculatesNextRun() {
        Workflow workflow = workflow();
        workflow.setStatus(WorkflowStatus.PAUSED);
        workflow.setCronExpression("0 0 * * * *");
        WorkflowDefinition definition =
                persistedDefinition(workflow, 1, definition("Done"));
        workflow.setActiveDefinition(definition);
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(aslDefinitionValidator.validate(any()))
                .thenReturn(new AslValidationResult(List.of()));

        var response = workflowService.resumeWorkflow(workflow.getId());

        assertThat(response.status()).isEqualTo(WorkflowStatus.ACTIVE);
        assertThat(response.nextRunAt()).isAfter(Instant.now());
        verify(workflowRepository).save(workflow);
    }

    @Test
    void archivesWorkflowAndPreventsReactivation() {
        Workflow workflow = workflow();
        workflow.setStatus(WorkflowStatus.PAUSED);
        workflow.setNextRunAt(Instant.now().plusSeconds(60));
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));

        var response = workflowService.archiveWorkflow(workflow.getId());

        assertThat(response.status()).isEqualTo(WorkflowStatus.ARCHIVED);
        assertThat(response.nextRunAt()).isNull();

        assertThatThrownBy(() -> workflowService.resumeWorkflow(workflow.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Archived workflows cannot be reactivated");
    }

    @Test
    void activatingRevisionRecalculatesSchedule() {
        Workflow workflow = workflow();
        workflow.setCronExpression("0 0 * * * *");
        WorkflowDefinition definition =
                persistedDefinition(workflow, 1, definition("Done"));
        workflow.setActiveDefinition(definition);
        when(workflowRepository.findByIdForUpdate(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowDefinitionRepository.findByWorkflowAndRevision(workflow, 1))
                .thenReturn(Optional.of(definition));
        when(aslDefinitionValidator.validate(any()))
                .thenReturn(new AslValidationResult(List.of()));

        workflowService.activateRevision(workflow.getId(), 1);

        assertThat(workflow.getNextRunAt()).isAfter(Instant.now());
    }

    @Test
    void listsWorkflowsWithPaginationAndFilters() {
        Workflow workflow = workflow();
        workflow.setStatus(WorkflowStatus.ACTIVE);
        when(workflowRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(workflow)));

        var page = workflowService.listWorkflows(
                -1,
                1000,
                WorkflowStatus.ACTIVE,
                "work"
        );

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).id()).isEqualTo(workflow.getId());
        assertThat(page.size()).isEqualTo(1);
    }

    @Test
    void updatesMetadataAndRecalculatesActiveSchedule() {
        Workflow workflow = workflow();
        workflow.setVersion(3);
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setCronExpression("0 0 * * * *");
        when(workflowRepository.findById(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowRepository.saveAndFlush(workflow))
                .thenReturn(workflow);

        var response = workflowService.updateMetadata(
                workflow.getId(),
                new UpdateWorkflowMetadataRequestDTO(
                        3L,
                        " Updated workflow ",
                        objectMapper.getNodeFactory()
                                .textNode("0 30 * * * *"),
                        "Asia/Kolkata",
                        5
                )
        );

        assertThat(response.name()).isEqualTo("Updated workflow");
        assertThat(response.cronExpression()).isEqualTo("0 30 * * * *");
        assertThat(response.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(response.maxAttempts()).isEqualTo(5);
        assertThat(response.nextRunAt()).isAfter(Instant.now());
    }

    @Test
    void explicitNullCronRemovesSchedule() {
        Workflow workflow = workflow();
        workflow.setVersion(2);
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setCronExpression("0 0 * * * *");
        when(workflowRepository.findById(workflow.getId()))
                .thenReturn(Optional.of(workflow));
        when(workflowRepository.saveAndFlush(workflow))
                .thenReturn(workflow);

        var response = workflowService.updateMetadata(
                workflow.getId(),
                new UpdateWorkflowMetadataRequestDTO(
                        2L,
                        null,
                        objectMapper.nullNode(),
                        null,
                        null
                )
        );

        assertThat(response.cronExpression()).isNull();
        assertThat(response.nextRunAt()).isNull();
    }

    @Test
    void rejectsStaleMetadataVersion() {
        Workflow workflow = workflow();
        workflow.setVersion(4);
        when(workflowRepository.findById(workflow.getId()))
                .thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> workflowService.updateMetadata(
                workflow.getId(),
                new UpdateWorkflowMetadataRequestDTO(
                        3L,
                        "Changed",
                        null,
                        null,
                        null
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version conflict");
    }

    private CreateWorkflowRequestDTO request(tools.jackson.databind.JsonNode definition) {
        return new CreateWorkflowRequestDTO(
                "Workflow",
                null,
                3,
                "workflow-key",
                "UTC",
                definition
        );
    }

    private tools.jackson.databind.JsonNode definition(String stateName) {
        return objectMapper.createObjectNode()
                .put("StartAt", stateName)
                .set("States", objectMapper.createObjectNode()
                        .set(stateName, objectMapper.createObjectNode().put("Type", "Succeed")));
    }

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID());
        workflow.setName("Workflow");
        workflow.setStatus(WorkflowStatus.DRAFT);
        workflow.setTimezone("UTC");
        workflow.setMaxAttempts(3);
        workflow.setIdempotencyKey("workflow-key");
        workflow.setCreatedAt(Instant.now());
        workflow.setUpdatedAt(Instant.now());
        return workflow;
    }

    private WorkflowDefinition persistedDefinition(
            Workflow workflow,
            long revision,
            tools.jackson.databind.JsonNode definitionNode
    ) {
        var canonical = new WorkflowDefinitionCanonicalizer(objectMapper)
                .canonicalize(definitionNode);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(UUID.randomUUID());
        definition.setWorkflow(workflow);
        definition.setRevision(revision);
        definition.setDefinition(canonical.json());
        definition.setDefinitionHash(canonical.hash());
        definition.setCreatedAt(Instant.now());
        return definition;
    }
}
