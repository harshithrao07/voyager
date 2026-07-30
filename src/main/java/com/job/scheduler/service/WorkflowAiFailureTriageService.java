package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowExecutionDetailDTO;
import com.job.scheduler.dto.WorkflowExecutionScopeDTO;
import com.job.scheduler.dto.WorkflowStateExecutionDTO;
import com.job.scheduler.dto.WorkflowTriagePatchDTO;
import com.job.scheduler.dto.WorkflowTriageResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowExecution;
import com.job.scheduler.enums.StateExecutionStatus;
import com.job.scheduler.enums.WorkflowExecutionStatus;
import com.job.scheduler.repository.WorkflowExecutionRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Diagnoses a failed workflow execution with the AI model: it feeds the failing state, its input and
 * error, and the full ASL to the model and returns a plain-English, evidence-based root cause.
 * Triage is intentionally read-only: it never proposes or applies workflow changes.
 *
 * <p>Deliberately self-contained — it resolves the model through {@link WorkflowAiModelResolver} and
 * calls {@code chat()} directly rather than reusing the authoring conversation's structured-output
 * path, so the two evolve independently.
 */
@Service
@RequiredArgsConstructor
public class WorkflowAiFailureTriageService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAiFailureTriageService.class);
    private static final Set<WorkflowExecutionStatus> FAILED_STATUSES =
            Set.of(WorkflowExecutionStatus.FAILED, WorkflowExecutionStatus.TIMED_OUT);
    private static final Set<StateExecutionStatus> FAILED_STATE_STATUSES =
            Set.of(StateExecutionStatus.FAILED, StateExecutionStatus.TIMED_OUT);
    private static final int MAX_ASL_CHARS = 24_000;

    private final WorkflowExecutionRepository workflowExecutionRepository;
    private final WorkflowExecutionInspectionService inspectionService;
    private final AiModelConfigService aiModelConfigService;
    private final WorkflowAiModelResolver modelResolver;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WorkflowTriageResponseDTO triage(UUID workflowId, UUID executionId, UUID modelConfigId) {
        WorkflowExecution execution = workflowExecutionRepository.findById(executionId)
                .filter(candidate -> candidate.getWorkflow().getId().equals(workflowId))
                .orElseThrow(() -> new EntityNotFoundException("Workflow execution does not exist"));
        if (!FAILED_STATUSES.contains(execution.getStatus())) {
            throw new IllegalStateException(
                    "Only failed or timed-out executions can be triaged; this one is "
                            + execution.getStatus());
        }

        String asl = execution.getWorkflowDefinition().getDefinition();
        WorkflowExecutionDetailDTO detail = inspectionService.getExecution(workflowId, executionId);
        WorkflowStateExecutionDTO failingState = findFailingState(detail).orElse(null);

        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt()),
                UserMessage.from(userPrompt(execution, failingState, asl))
        );

        AiModelConfig modelConfig = aiModelConfigService.resolveModel(modelConfigId);
        ChatModel model = modelResolver.resolve(modelConfig);
        ChatResponse response = model.chat(messages);
        String raw = response.aiMessage() == null ? null : response.aiMessage().text();

        JsonNode parsed = parseLenientJson(raw);
        String rootCause = textField(parsed, "rootCause", "The model did not identify a specific cause.");
        String explanation = textField(parsed, "explanation", "");
        return new WorkflowTriageResponseDTO(
                executionId,
                failingState == null ? null : failingState.stateName(),
                rootCause,
                explanation,
                WorkflowTriagePatchDTO.none()
        );
    }

    private Optional<WorkflowStateExecutionDTO> findFailingState(WorkflowExecutionDetailDTO detail) {
        WorkflowStateExecutionDTO withError = null;
        for (WorkflowExecutionScopeDTO scope : detail.scopes()) {
            for (WorkflowStateExecutionDTO state : scope.stateExecutions()) {
                if (FAILED_STATE_STATUSES.contains(state.status())) {
                    // Prefer a failing state that captured an error message.
                    if (state.error() != null && !state.error().isBlank()) {
                        return Optional.of(state);
                    }
                    if (withError == null) {
                        withError = state;
                    }
                }
            }
        }
        return Optional.ofNullable(withError);
    }

    private String systemPrompt() {
        return """
                You are Voyager's workflow failure-triage assistant. Voyager runs Amazon States
                Language (ASL) workflows that use JSONata (in {% ... %}) for data flow, plus Task
                resources like voyager://function/<name> and voyager://mcp/<server>/<tool>.

                Given a failed execution, identify what caused the failure and explain the evidence
                from the execution status, error, state input, state error, and workflow definition.
                This is diagnosis only. Do not propose, recommend, or describe any fix, remediation,
                retry, workflow edit, corrected expression, code change, patch, or next step. Do not
                return an ASL definition, changes list, instructions, or suggestions.

                Respond with a single JSON object and nothing else:
                {
                  "rootCause": "one or two sentences identifying the direct cause",
                  "explanation": "a short evidence-based paragraph explaining how the observed input, state, or error caused the failure"
                }
                Never include comments or trailing commas in the JSON.
                """;
    }

    private String userPrompt(WorkflowExecution execution, WorkflowStateExecutionDTO failingState, String asl) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("A workflow execution failed. Diagnose it.\n\n");
        prompt.append("Execution status: ").append(execution.getStatus()).append('\n');
        appendIfPresent(prompt, "Execution error", execution.getError());
        appendIfPresent(prompt, "Execution cause", execution.getCause());
        appendIfPresent(prompt, "Execution input", execution.getInput());
        prompt.append('\n');

        if (failingState != null) {
            prompt.append("Failing state: ").append(failingState.stateName())
                    .append(" (").append(failingState.stateType()).append(")\n");
            appendIfPresent(prompt, "State resource", failingState.resource());
            appendIfPresent(prompt, "State input", stringify(failingState.input()));
            appendIfPresent(prompt, "State error", failingState.error());
            appendIfPresent(prompt, "State cause", failingState.cause());
            prompt.append('\n');
        }

        prompt.append("Workflow ASL definition:\n").append(truncate(asl));
        return prompt.toString();
    }

    private void appendIfPresent(StringBuilder prompt, String label, String value) {
        if (value != null && !value.isBlank()) {
            prompt.append(label).append(": ").append(value).append('\n');
        }
    }

    private String stringify(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private String truncate(String value) {
        if (value == null) {
            return "(unavailable)";
        }
        return value.length() <= MAX_ASL_CHARS
                ? value
                : value.substring(0, MAX_ASL_CHARS) + "\n... (truncated)";
    }

    private String textField(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? value.stringValue()
                : fallback;
    }

    /**
     * Extracts the JSON object from a model reply that may wrap it in prose or a ```json fence.
     * Returns null when nothing parseable is found so the caller falls back to a text-only diagnosis.
     */
    JsonNode parseLenientJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        // Drop a leading reasoning block some local models emit before the JSON.
        int think = text.lastIndexOf("</think>");
        if (think >= 0) {
            text = text.substring(think + "</think>".length()).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(text.substring(start, end + 1));
        } catch (RuntimeException exception) {
            log.debug("Triage response was not parseable JSON: {}", exception.getMessage());
            return null;
        }
    }
}
