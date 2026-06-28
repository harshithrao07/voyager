package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class WorkflowAiModelResolver {
    public ChatLanguageModel resolve(AiModelConfig config) {
        if (config.getProviderType() != AiModelProviderType.OPENAI_COMPATIBLE_LOCAL) {
            throw new IllegalArgumentException(
                    "Unsupported AI model provider: " + config.getProviderType()
            );
        }
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey() == null || config.getApiKey().isBlank()
                        ? "local"
                        : config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(90))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
