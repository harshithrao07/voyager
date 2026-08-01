package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.entity.FunctionDefinition;
import com.job.scheduler.entity.FunctionVersion;
import com.job.scheduler.entity.McpTool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CatalogDescriptionGenerationService {
    private static final Logger log = LoggerFactory.getLogger(CatalogDescriptionGenerationService.class);
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final AiModelConfigService aiModelConfigService;
    private final WorkflowAiModelResolver modelResolver;

    public String describeFunction(FunctionDefinition function, FunctionVersion version) {
        String source = version.getSourceCode() == null
                ? "Multi-file function source is stored as an archive."
                : bounded(version.getSourceCode(), 8_000);
        return generate("""
                Write one precise catalog description for a workflow function. Explain what it does,
                its expected JSON input, and its JSON output when those are evident. Use plain text,
                one or two sentences, no markdown, no marketing language, and no invented behavior.
                """, "Function name: " + function.getName()
                + "\nLanguage id: " + version.getLanguageId()
                + "\nSource mode: " + version.getSourceMode()
                + "\nSource:\n" + source);
    }

    public String describeMcpTool(McpTool tool) {
        return generate("""
                Write one precise catalog description for an MCP tool. Explain the capability and
                important inputs evident from its schema. Use plain text, one or two sentences, no
                markdown, no marketing language, and no invented behavior.
                """, "Server: " + tool.getMcpServer().getServerId()
                + "\nTool name: " + tool.getToolName()
                + "\nTitle: " + nullToEmpty(tool.getTitle())
                + "\nInput schema:\n" + bounded(tool.getInputSchema(), 6_000)
                + "\nOutput schema:\n" + bounded(tool.getOutputSchema(), 3_000));
    }

    private String generate(String systemPrompt, String userPrompt) {
        try {
            AiModelConfig config = aiModelConfigService.resolveModel(null);
            ChatModel model = modelResolver.resolve(config);
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)
            );
            String value = clean(model.chat(messages).aiMessage().text());
            return value == null || value.isBlank() ? null : bounded(value, MAX_DESCRIPTION_LENGTH);
        } catch (RuntimeException exception) {
            log.warn("Could not generate catalog description: {}", exception.getMessage());
            return null;
        }
    }

    private String clean(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        int thinkingEnd = value.lastIndexOf("</think>");
        if (thinkingEnd >= 0) value = value.substring(thinkingEnd + 8).trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int firstLine = value.indexOf('\n');
            value = firstLine >= 0 ? value.substring(firstLine + 1, value.length() - 3).trim() : value;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private String bounded(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit).trim() + "…";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
