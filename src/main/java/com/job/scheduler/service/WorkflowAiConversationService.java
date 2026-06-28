package com.job.scheduler.service;

import com.job.scheduler.dto.CreateWorkflowRequestDTO;
import com.job.scheduler.dto.WorkflowAiResponseDTO;
import com.job.scheduler.dto.WorkflowResponseDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.WorkflowAiConversation;
import com.job.scheduler.entity.WorkflowAiMessage;
import com.job.scheduler.enums.WorkflowAiConversationStage;
import com.job.scheduler.enums.WorkflowAiMessageRole;
import com.job.scheduler.enums.WorkflowPriority;
import com.job.scheduler.repository.WorkflowAiConversationRepository;
import com.job.scheduler.repository.WorkflowAiMessageRepository;
import com.job.scheduler.workflow.asl.runtime.AslRuntimeCapabilityValidator;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAiConversationService {
    private static final String SYSTEM_PROMPT = """
            You are Voyager's workflow builder for this scheduler.
            Return strict JSON only with these fields:
            {
              "stage": "COLLECTING_WORKFLOW_DETAILS|ASL_READY|COLLECTING_SCHEDULE_DETAILS|PLAN_READY",
              "message": "short assistant message for the user",
              "aslDefinition": optional JSONata-only ASL object,
              "finalPlan": optional object,
              "draftWorkflowPayload": optional object with name, priority, cronExpression, timezone, maxAttempts, idempotencyKey, definition
            }
            ASL rules: omit QueryLanguage and Version, use JSONata expressions with {% %}, reject JSONPath fields and States.* intrinsics.
            Keep cron, timezone, priority, approval, and schedule metadata outside ASL.
            Ask clarifying questions until the workflow is clear. When ASL is ready, include aslDefinition.
            After ASL is approved, collect workflow name, cron expression if scheduled, timezone, priority, and max attempts.
            When everything is ready, return PLAN_READY with finalPlan and draftWorkflowPayload.
            """;

    private final AiModelConfigService aiModelConfigService;
    private final WorkflowAiModelResolver modelResolver;
    private final WorkflowAiConversationRepository conversationRepository;
    private final WorkflowAiMessageRepository messageRepository;
    private final AslDefinitionValidator aslDefinitionValidator;
    private final AslRuntimeCapabilityValidator runtimeCapabilityValidator;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowAiResponseDTO startConversation(
            String instruction,
            UUID modelConfigId,
            String userDateTime
    ) {
        String normalizedInstruction = requireText(instruction, "Instruction");
        AiModelConfig modelConfig = aiModelConfigService.resolveModel(modelConfigId);

        WorkflowAiConversation conversation = new WorkflowAiConversation();
        conversation.setName(generateConversationName(normalizedInstruction));
        conversation.setInitialInstruction(normalizedInstruction);
        conversation.setModelConfig(modelConfig);
        conversation.setStage(
                WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS
        );
        conversationRepository.save(conversation);
        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                withDateContext(normalizedInstruction, userDateTime),
                null
        );

        return callAssistant(conversation, "Start from the user's first instruction.");
    }

    @Transactional
    public WorkflowAiResponseDTO continueConversation(
            UUID conversationId,
            String message
    ) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                requireText(message, "Message"),
                null
        );
        return callAssistant(conversation, "Continue the workflow design conversation.");
    }

    @Transactional
    public WorkflowAiResponseDTO reviewAsl(UUID conversationId, JsonNode definition) {
        if (definition == null || definition.isNull()) {
            throw new IllegalArgumentException("ASL definition cannot be null");
        }
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        List<String> validationIssues = validateExecutableDefinition(definition);
        String reviewInstruction = """
                The user edited the ASL in the editor. Compare it against the original request and the conversation.
                If the ASL is valid and matches the request, move to COLLECTING_SCHEDULE_DETAILS.
                If it needs changes, move to ASL_UNDER_REVIEW and explain the smallest correction.
                Current edited ASL:
                """ + serialize(definition);
        if (!validationIssues.isEmpty()) {
            reviewInstruction += "\nValidator issues:\n" + String.join("\n", validationIssues);
        }
        appendMessage(
                conversation,
                WorkflowAiMessageRole.USER,
                reviewInstruction,
                serialize(definition)
        );

        WorkflowAiResponseDTO assistantResponse =
                callAssistant(conversation, "Review the edited ASL.");

        if (!validationIssues.isEmpty()) {
            conversation.setStage(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
            return response(
                    conversation,
                    assistantResponse.message(),
                    definition,
                    validationIssues,
                    readJson(conversation.getFinalPlan()),
                    readDraftPayload(conversation),
                    null
            );
        }

        conversation.setDraftAsl(serialize(definition));
        if (conversation.getStage() == WorkflowAiConversationStage.ASL_UNDER_REVIEW
                || conversation.getStage() == WorkflowAiConversationStage.ASL_READY) {
            conversation.setStage(
                    WorkflowAiConversationStage.COLLECTING_SCHEDULE_DETAILS
            );
        }
        return response(
                conversation,
                assistantResponse.message(),
                definition,
                List.of(),
                readJson(conversation.getFinalPlan()),
                readDraftPayload(conversation),
                null
        );
    }

    @Transactional
    public WorkflowAiResponseDTO acceptPlan(UUID conversationId) {
        WorkflowAiConversation conversation = findForUpdate(conversationId);
        CreateWorkflowRequestDTO draftPayload = readDraftPayload(conversation);
        if (draftPayload == null) {
            draftPayload = fallbackDraftPayload(conversation);
        }
        WorkflowResponseDTO workflow = workflowService.createWorkflow(draftPayload);
        conversation.setStage(WorkflowAiConversationStage.ACCEPTED);
        appendMessage(
                conversation,
                WorkflowAiMessageRole.SYSTEM,
                "Accepted final plan and created draft workflow " + workflow.id(),
                null
        );
        return response(
                conversation,
                "Draft workflow created.",
                readJson(conversation.getDraftAsl()),
                List.of(),
                readJson(conversation.getFinalPlan()),
                draftPayload,
                workflow
        );
    }

    private WorkflowAiResponseDTO callAssistant(
            WorkflowAiConversation conversation,
            String task
    ) {
        ChatLanguageModel model = modelResolver.resolve(conversation.getModelConfig());
        List<ChatMessage> messages = buildPrompt(conversation, task);
        String raw = model.generate(messages).content().text().trim();
        String cleaned = stripMarkdown(raw);
        ParsedAssistantResponse parsed = parseAssistantResponse(cleaned);

        JsonNode aslDefinition = parsed.aslDefinition();
        List<String> validationIssues = List.of();
        if (aslDefinition != null) {
            validationIssues = validateExecutableDefinition(aslDefinition);
            if (validationIssues.isEmpty()) {
                conversation.setDraftAsl(serialize(aslDefinition));
                conversation.setStage(WorkflowAiConversationStage.ASL_READY);
            } else {
                conversation.setStage(WorkflowAiConversationStage.ASL_UNDER_REVIEW);
            }
        } else if (parsed.draftWorkflowPayload() != null) {
            conversation.setDraftWorkflowPayload(
                    serialize(parsed.draftWorkflowPayloadNode())
            );
            conversation.setFinalPlan(serialize(parsed.finalPlan()));
            conversation.setStage(WorkflowAiConversationStage.PLAN_READY);
        } else if (parsed.stage() != null) {
            conversation.setStage(parsed.stage());
        }

        appendMessage(
                conversation,
                WorkflowAiMessageRole.ASSISTANT,
                parsed.message() == null ? cleaned : parsed.message(),
                cleaned
        );

        return response(
                conversation,
                parsed.message() == null ? cleaned : parsed.message(),
                aslDefinition == null ? readJson(conversation.getDraftAsl()) : aslDefinition,
                validationIssues,
                parsed.finalPlan() == null
                        ? readJson(conversation.getFinalPlan())
                        : parsed.finalPlan(),
                parsed.draftWorkflowPayload() == null
                        ? readDraftPayload(conversation)
                        : parsed.draftWorkflowPayload(),
                null
        );
    }

    private List<ChatMessage> buildPrompt(
            WorkflowAiConversation conversation,
            String task
    ) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(SYSTEM_PROMPT));
        messages.add(SystemMessage.systemMessage(
                "Current stage: " + conversation.getStage()
                        + "\nInitial request: " + conversation.getInitialInstruction()
                        + "\nTask: " + task
        ));
        for (WorkflowAiMessage message :
                messageRepository.findByConversationOrderByCreatedAtAsc(conversation)) {
            if (message.getRole() == WorkflowAiMessageRole.ASSISTANT) {
                messages.add(AiMessage.aiMessage(message.getContent()));
            } else if (message.getRole() == WorkflowAiMessageRole.USER) {
                messages.add(UserMessage.userMessage(message.getContent()));
            }
        }
        return messages;
    }

    private ParsedAssistantResponse parseAssistantResponse(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (looksLikeAsl(root)) {
                return new ParsedAssistantResponse(
                        WorkflowAiConversationStage.ASL_READY,
                        "ASL definition is ready.",
                        root,
                        null,
                        null,
                        null
                );
            }
            WorkflowAiConversationStage stage = parseStage(root.path("stage"));
            String message = root.path("message").isString()
                    ? root.path("message").stringValue()
                    : null;
            JsonNode asl = root.get("aslDefinition");
            JsonNode finalPlan = root.get("finalPlan");
            JsonNode draftPayloadNode = root.get("draftWorkflowPayload");
            CreateWorkflowRequestDTO draftPayload = draftPayloadNode == null
                    || draftPayloadNode.isNull()
                    ? null
                    : objectMapper.treeToValue(
                            draftPayloadNode,
                            CreateWorkflowRequestDTO.class
                    );
            return new ParsedAssistantResponse(
                    stage,
                    message,
                    asl,
                    finalPlan,
                    draftPayload,
                    draftPayloadNode
            );
        } catch (Exception exception) {
            log.warn("Could not parse workflow AI response as structured JSON", exception);
            return new ParsedAssistantResponse(
                    WorkflowAiConversationStage.COLLECTING_WORKFLOW_DETAILS,
                    value,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    private List<String> validateExecutableDefinition(JsonNode definition) {
        AslValidationResult validation = aslDefinitionValidator.validate(definition);
        List<AslValidationIssue> issues = new ArrayList<>(validation.issues());
        issues.addAll(runtimeCapabilityValidator.validate(definition));
        return issues.stream()
                .map(issue -> String.format(
                        "[%s] %s at %s: %s",
                        issue.category(),
                        issue.code(),
                        issue.location(),
                        issue.message()
                ))
                .toList();
    }

    private WorkflowAiConversation findForUpdate(UUID conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation id cannot be null");
        }
        return conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workflow AI conversation does not exist"
                ));
    }

    private void appendMessage(
            WorkflowAiConversation conversation,
            WorkflowAiMessageRole role,
            String content,
            String structuredPayload
    ) {
        WorkflowAiMessage message = new WorkflowAiMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setStructuredPayload(structuredPayload);
        messageRepository.save(message);
    }

    private WorkflowAiResponseDTO response(
            WorkflowAiConversation conversation,
            String message,
            JsonNode aslDefinition,
            List<String> validationIssues,
            JsonNode finalPlan,
            CreateWorkflowRequestDTO draftWorkflowPayload,
            WorkflowResponseDTO workflow
    ) {
        return new WorkflowAiResponseDTO(
                conversation.getId(),
                conversation.getName(),
                conversation.getStage(),
                message,
                aslDefinition,
                validationIssues == null ? List.of() : validationIssues,
                finalPlan,
                draftWorkflowPayload,
                workflow == null ? null : workflow.id(),
                workflow
        );
    }

    private CreateWorkflowRequestDTO fallbackDraftPayload(
            WorkflowAiConversation conversation
    ) {
        JsonNode definition = readJson(conversation.getDraftAsl());
        if (definition == null) {
            throw new IllegalStateException("No reviewed ASL definition is available");
        }
        return new CreateWorkflowRequestDTO(
                conversation.getName(),
                WorkflowPriority.MEDIUM,
                null,
                3,
                "workflow-ai-" + conversation.getId(),
                "UTC",
                definition
        );
    }

    private CreateWorkflowRequestDTO readDraftPayload(
            WorkflowAiConversation conversation
    ) {
        if (conversation.getDraftWorkflowPayload() == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(
                    objectMapper.readTree(conversation.getDraftWorkflowPayload()),
                    CreateWorkflowRequestDTO.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not read stored draft workflow payload",
                    exception
            );
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read stored JSON", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize value", exception);
        }
    }

    private WorkflowAiConversationStage parseStage(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return WorkflowAiConversationStage.valueOf(
                    node.stringValue().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean looksLikeAsl(JsonNode root) {
        return root != null && root.isObject() && root.has("StartAt") && root.has("States");
    }

    private String stripMarkdown(String rawOutput) {
        String output = rawOutput == null ? "" : rawOutput.trim();
        if (output.startsWith("```json")) {
            output = output.substring(7);
        }
        if (output.startsWith("```")) {
            output = output.substring(3);
        }
        if (output.endsWith("```")) {
            output = output.substring(0, output.length() - 3);
        }
        return output.trim();
    }

    private String generateConversationName(String instruction) {
        String compact = instruction.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 48) {
            return compact;
        }
        return compact.substring(0, 48).trim();
    }

    private String withDateContext(String instruction, String userDateTime) {
        if (userDateTime == null || userDateTime.isBlank()) {
            return instruction;
        }
        return instruction + "\n\nUser date/time context: " + userDateTime.trim();
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value.trim();
    }

    private record ParsedAssistantResponse(
            WorkflowAiConversationStage stage,
            String message,
            JsonNode aslDefinition,
            JsonNode finalPlan,
            CreateWorkflowRequestDTO draftWorkflowPayload,
            JsonNode draftWorkflowPayloadNode
    ) {
    }
}
