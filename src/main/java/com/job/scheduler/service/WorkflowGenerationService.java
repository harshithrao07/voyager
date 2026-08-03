package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowGenerationResponseDTO;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowGenerationService {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final WorkflowAiResourceCatalogService resourceCatalogService;
    private final AslDefinitionValidator validator;
    private final ObjectMapper objectMapper;

    private static final int MAX_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            Generate a JSONata-only ASL workflow for the user instruction.
            
            RULES:
            1. ONLY JSONata inside `{%% %%}` for dynamic fields (e.g. "{%% $states.input.x %%}").
            2. NO JSONPath (`.$` or `$.`).
            3. Valid states: Task, Pass, Choice, Wait, Parallel, Map, Succeed, Fail.
            4. Map states: ONLY `ItemProcessor` with `Mode: INLINE`.
            5. Keep state names short and descriptive.
            6. Do not include unnecessary Pass states or fluff.
            7. OUTPUT STRICTLY RAW JSON. No markdown, no explanations.
            
            RESOURCES:
            - voyager://system/webhook: args url(str), method(str optional, default POST),
              headers(obj<string,string> optional), body(any optional),
              includeExecutionContextHeaders(bool optional, default false). Supported methods:
              GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS.
            - voyager://system/send-email: args to(str), subject(str), body(str)

            FUNCTIONS (voyager://function/<name>; omit @version for the active one):
            %s

            MCP TOOLS (voyager://mcp/<serverId>/<toolName>; append ?trust=WRITE or ?trust=DESTRUCTIVE for non-read tools):
            %s
            """;

    public WorkflowGenerationResponseDTO generateWorkflow(String instruction) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("AI ChatModel is not configured. Please configure an AI provider for LangChain4j.");
        }

        String systemPrompt = String.format(
                SYSTEM_PROMPT_TEMPLATE,
                resourceCatalogService.buildFunctionsDocumentation(),
                resourceCatalogService.buildMcpToolsDocumentation());
        
        SystemMessage sysMsg = SystemMessage.systemMessage(systemPrompt);
        UserMessage userMsg = UserMessage.userMessage(instruction);

        String rawOutput = "";
        JsonNode parsedDefinition = null;
        List<String> validationIssues = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("Generating workflow attempt {}/{}", attempt, MAX_ATTEMPTS);
            ChatResponse response = chatModel.chat(List.of(sysMsg, userMsg));
            rawOutput = response.aiMessage().text().trim();
            
            // Remove markdown formatting if present
            if (rawOutput.startsWith("```json")) {
                rawOutput = rawOutput.substring(7);
            }
            if (rawOutput.startsWith("```")) {
                rawOutput = rawOutput.substring(3);
            }
            if (rawOutput.endsWith("```")) {
                rawOutput = rawOutput.substring(0, rawOutput.length() - 3);
            }
            rawOutput = rawOutput.trim();

            try {
                parsedDefinition = objectMapper.readTree(rawOutput);
                AslValidationResult result = validator.validate(parsedDefinition);
                if (result.isValid()) {
                    return new WorkflowGenerationResponseDTO(parsedDefinition, rawOutput, List.of());
                } else {
                    validationIssues = result.issues().stream()
                            .map(issue -> String.format("[%s] %s at %s: %s", issue.category(), issue.code(), issue.location(), issue.message()))
                            .collect(Collectors.toList());
                    
                    log.warn("Validation failed on attempt {}: {}", attempt, validationIssues);
                    
                    if (attempt < MAX_ATTEMPTS) {
                        String feedback = "The generated ASL had validation errors. Please fix them and output the corrected JSON:\n" 
                                + String.join("\n", validationIssues);
                        userMsg = UserMessage.userMessage(instruction + "\n\n" + feedback);
                    }
                }
            } catch (Exception e) {
                validationIssues = List.of("JSON Parsing Error: " + e.getMessage());
                log.warn("Parsing failed on attempt {}", attempt, e);
                if (attempt < MAX_ATTEMPTS) {
                    userMsg = UserMessage.userMessage(instruction + "\n\nYour previous output was not valid JSON. Please return ONLY raw valid JSON.");
                }
            }
        }

        return new WorkflowGenerationResponseDTO(parsedDefinition, rawOutput, validationIssues);
    }

}
