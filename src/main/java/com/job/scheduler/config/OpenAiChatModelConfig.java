package com.job.scheduler.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the single properties-configured {@link ChatModel} used by the legacy one-shot generator
 * ({@code POST /app/v1/workflows/generate}).
 *
 * <p>This used to come from {@code langchain4j-open-ai-spring-boot-starter}, which is only published
 * as a 1.x beta. The starter's autoconfiguration was the only thing the project used it for, so it
 * is reproduced here from the same {@code langchain4j.open-ai.chat-model.*} properties rather than
 * pinning the whole application to a beta artifact.
 *
 * <p>The conversational generator does not use this bean: it resolves a model per conversation from
 * the encrypted AI model registry — see {@code WorkflowAiModelResolver}.
 */
@Configuration
public class OpenAiChatModelConfig {

    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.open-ai.chat-model", name = "api-key")
    public ChatModel openAiChatModel(
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.chat-model.log-responses:false}") boolean logResponses
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
