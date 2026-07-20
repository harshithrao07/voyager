package com.job.scheduler.repository;

import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.enums.WorkflowAiWorkspaceKind;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface WorkflowAiConversationRepository
        extends JpaRepository<WorkflowAiConversation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT conversation FROM WorkflowAiConversation conversation WHERE conversation.id = :conversationId")
    Optional<WorkflowAiConversation> findByIdForUpdate(
            @Param("conversationId") UUID conversationId
    );

    List<WorkflowAiConversation> findTop50ByWorkspaceKindOrderByUpdatedAtDesc(
            WorkflowAiWorkspaceKind workspaceKind
    );

    List<WorkflowAiConversation> findAllByWorkspaceKind(
            WorkflowAiWorkspaceKind workspaceKind
    );
}
