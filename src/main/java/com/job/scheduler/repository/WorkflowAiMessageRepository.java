package com.job.scheduler.repository;

import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowAiMessageRepository
        extends JpaRepository<WorkflowAiMessage, UUID> {
    List<WorkflowAiMessage> findByConversationOrderByCreatedAtAsc(
            WorkflowAiConversation conversation
    );

    Optional<WorkflowAiMessage> findFirstByConversationOrderByCreatedAtDesc(
            WorkflowAiConversation conversation
    );

    // regenerated_from_message_id is a self-reference with NO ACTION, which Postgres defers to the
    // end of the statement, so deleting an entire conversation's messages in one statement is safe.
    @Modifying
    @Query("DELETE FROM WorkflowAiMessage message WHERE message.conversation.id = :conversationId")
    void deleteByConversationId(@Param("conversationId") UUID conversationId);
}
