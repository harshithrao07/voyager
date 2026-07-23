package com.job.scheduler.dto;

import java.util.UUID;

/**
 * One incremental frame pushed to the browser while a workflow-AI turn is still running.
 *
 * <p>A turn is not a single model call: {@code callAssistant} can run an initial generation, a
 * function-creation review pass, and up to two repair passes. {@code pass} identifies which model
 * call produced the frame so the client can reset its live bubble when a later pass supersedes an
 * earlier one.
 *
 * <p>Only reasoning text is streamed verbatim. The model's answer is a strict JSON envelope that is
 * parsed, validated, and sometimes discarded, so answer frames carry progress only and the final
 * prose still arrives on the completed {@link WorkflowAiResponseDTO}.
 */
public record WorkflowAiStreamEventDTO(
        UUID conversationId,
        Type type,
        String stage,
        String text,
        int pass,
        int answerCharacters
) {
    public enum Type {
        /** A new model call started; {@code stage} labels it for the UI. */
        STAGE,
        /** Verbatim delta from inside a {@code <think>} block. */
        THINKING_DELTA,
        /** Progress only: the answer envelope grew by {@code answerCharacters} characters. */
        ANSWER_PROGRESS,
        /** The turn failed before a final response could be sent. */
        ERROR
    }

    public static WorkflowAiStreamEventDTO stage(UUID conversationId, String stage, int pass) {
        return new WorkflowAiStreamEventDTO(conversationId, Type.STAGE, stage, null, pass, 0);
    }

    public static WorkflowAiStreamEventDTO thinking(UUID conversationId, String text, int pass) {
        return new WorkflowAiStreamEventDTO(
                conversationId,
                Type.THINKING_DELTA,
                null,
                text,
                pass,
                0
        );
    }

    public static WorkflowAiStreamEventDTO answerProgress(
            UUID conversationId,
            int answerCharacters,
            int pass
    ) {
        return new WorkflowAiStreamEventDTO(
                conversationId,
                Type.ANSWER_PROGRESS,
                null,
                null,
                pass,
                answerCharacters
        );
    }

    public static WorkflowAiStreamEventDTO error(UUID conversationId, String message) {
        return new WorkflowAiStreamEventDTO(conversationId, Type.ERROR, null, message, 0, 0);
    }
}
