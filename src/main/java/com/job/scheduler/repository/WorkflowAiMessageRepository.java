package com.job.scheduler.repository;

import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowAiMessageRepository
        extends JpaRepository<WorkflowAiMessage, UUID> {
    List<WorkflowAiMessage> findByConversationOrderByCreatedAtAsc(
            WorkflowAiConversation conversation
    );
}
