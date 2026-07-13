package com.job.scheduler.service;

import com.job.scheduler.dto.WorkflowGenerationResponseDTO;
import com.job.scheduler.entity.McpTool;
import com.job.scheduler.repository.McpToolRepository;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidator;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import com.job.scheduler.workflow.asl.validation.AslValidationResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
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

    private final ObjectProvider<ChatLanguageModel> chatLanguageModelProvider;
    private final McpToolRepository mcpToolRepository;
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
            - voyager://webhook: args url(str), body(obj)
            - voyager://send-email: args to(str), subject(str), body(str)
            
            MCP TOOLS:
            %s
            """;

    public WorkflowGenerationResponseDTO generateWorkflow(String instruction) {
        ChatLanguageModel chatLanguageModel = chatLanguageModelProvider.getIfAvailable();
        if (chatLanguageModel == null) {
            throw new IllegalStateException("AI ChatLanguageModel is not configured. Please configure an AI provider for LangChain4j.");
        }

        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, buildMcpToolsDocumentation());
        
        SystemMessage sysMsg = SystemMessage.systemMessage(systemPrompt);
        UserMessage userMsg = UserMessage.userMessage(instruction);

        String rawOutput = "";
        JsonNode parsedDefinition = null;
        List<String> validationIssues = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("Generating workflow attempt {}/{}", attempt, MAX_ATTEMPTS);
            Response<AiMessage> response = chatLanguageModel.generate(List.of(sysMsg, userMsg));
            rawOutput = response.content().text().trim();
            
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

    private String buildMcpToolsDocumentation() {
        List<McpTool> tools = mcpToolRepository.findByEnabledTrue();
        if (tools.isEmpty()) {
            return "None.";
        }

        StringBuilder doc = new StringBuilder();
        for (McpTool tool : tools) {
            doc.append("- mcp://").append(tool.getMcpServer().getServerId()).append("/").append(tool.getToolName());
            if (tool.getInputSchema() != null) {
                doc.append(" args: ").append(flattenJsonSchema(tool.getInputSchema()));
            }
            if (tool.getDescription() != null) {
                doc.append(" (").append(tool.getDescription()).append(")");
            }
            doc.append("\n");
        }
        return doc.toString();
    }

    private String flattenJsonSchema(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) return "{}";
        try {
            JsonNode root = objectMapper.readTree(jsonSchema);
            JsonNode properties = root.path("properties");
            if (properties.isMissingNode() || !properties.isObject()) return "{}";
            
            StringBuilder sb = new StringBuilder("{");
            for (java.util.Map.Entry<String, JsonNode> entry : properties.properties()) {
                String name = entry.getKey();
                JsonNode typeNode = entry.getValue().path("type");
                String type = typeNode.isTextual() ? typeNode.asText() : "any";
                sb.append(name).append(":").append(type).append(",");
            }
            if (sb.length() > 1) {
                sb.setLength(sb.length() - 1); // remove trailing comma
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
