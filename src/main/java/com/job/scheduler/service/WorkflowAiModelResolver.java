package com.job.scheduler.service;

import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class WorkflowAiModelResolver {
    private final SecretResolver secretResolver;

    public ChatLanguageModel resolve(AiModelConfig config) {
        if (config.getProviderType() != AiModelProviderType.OPENAI_COMPATIBLE_LOCAL
                && config.getProviderType() != AiModelProviderType.OPENAI_COMPATIBLE_API) {
            throw new IllegalArgumentException(
                    "Unsupported AI model provider: " + config.getProviderType()
            );
        }
        String credential = config.getCredentialRef() == null
                || config.getCredentialRef().isBlank()
                ? "local"
                : secretResolver.require(config.getCredentialRef());
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(credential)
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(90))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
