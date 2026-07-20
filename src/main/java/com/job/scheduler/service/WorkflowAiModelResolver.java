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
    private final SecretCipher secretCipher;

    public ChatLanguageModel resolve(AiModelConfig config) {
        if (config.getProviderType() != AiModelProviderType.OPENAI_COMPATIBLE_LOCAL
                && config.getProviderType() != AiModelProviderType.OPENAI_COMPATIBLE_API) {
            throw new IllegalArgumentException(
                    "Unsupported AI model provider: " + config.getProviderType()
            );
        }
        String decrypted = secretCipher.decrypt(config.getCredentialEncrypted());
        String credential = decrypted == null || decrypted.isBlank() ? "local" : decrypted;
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(credential)
                .modelName(config.getModelName())
                // Reasoning-capable local models emit a <think> block before the JSON; a long
                // proposal (function sourceCode + MCP requirements) can otherwise be cut off.
                .timeout(Duration.ofSeconds(150))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
