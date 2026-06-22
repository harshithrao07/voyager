package com.job.scheduler.repository;

import com.job.scheduler.entity.Workflow;
import com.job.scheduler.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {
    List<WorkflowDefinition> findByWorkflowOrderByRevisionDesc(Workflow workflow);

    Optional<WorkflowDefinition> findByWorkflowAndRevision(Workflow workflow, long revision);

    Optional<WorkflowDefinition> findFirstByWorkflowOrderByRevisionDesc(Workflow workflow);

    Optional<WorkflowDefinition> findByWorkflowAndDefinitionHash(
            Workflow workflow,
            String definitionHash
    );
}
