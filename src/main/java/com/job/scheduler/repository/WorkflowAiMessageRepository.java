package com.job.scheduler.repository;

import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /** Latest message of a role — used to recover a turn's user input for observability tracing. */
    Optional<WorkflowAiMessage> findFirstByConversationAndRoleOrderByCreatedAtDesc(
            WorkflowAiConversation conversation,
            WorkflowAiMessageRole role
    );

    // regenerated_from_message_id is a self-reference with NO ACTION, which Postgres defers to the
    // end of the statement, so deleting an entire conversation's messages in one statement is safe.
    @Modifying
    @Query("DELETE FROM WorkflowAiMessage message WHERE message.conversation.id = :conversationId")
    void deleteByConversationId(@Param("conversationId") UUID conversationId);

    /** Flat per-turn telemetry projection (avoids loading entities + the lazy modelConfig join). */
    interface TurnTelemetryRow {
        Instant getCreatedAt();
        Long getDurationMs();
        Integer getInputTokens();
        Integer getOutputTokens();
        Integer getTotalTokens();
        String getFinishReason();
        String getModelName();
    }

    /**
     * Assistant-message telemetry since a cutoff, newest first, for the observability metrics view.
     * Only ASSISTANT rows carry duration/token/finishReason; the model name comes from the message's
     * model config.
     */
    @Query("""
            SELECT m.createdAt AS createdAt,
                   m.durationMs AS durationMs,
                   m.inputTokens AS inputTokens,
                   m.outputTokens AS outputTokens,
                   m.totalTokens AS totalTokens,
                   m.finishReason AS finishReason,
                   mc.modelName AS modelName
            FROM WorkflowAiMessage m
            LEFT JOIN m.modelConfig mc
            WHERE m.role = :role AND m.createdAt >= :since
            ORDER BY m.createdAt DESC
            """)
    List<TurnTelemetryRow> findTurnTelemetrySince(
            @Param("role") WorkflowAiMessageRole role,
            @Param("since") Instant since,
            Pageable pageable
    );
}
