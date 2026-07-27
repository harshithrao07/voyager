package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelConfigRequestDTO;
import com.job.scheduler.dto.AiModelTestRequestDTO;
import com.job.scheduler.dto.AiModelTestResponseDTO;
import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.repository.AiModelConfigRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AiModelConfigService {
    private final AiModelConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Transactional
    public List<AiModelConfigDTO> listEnabledModels() {
        return repository.findByEnabledTrueOrderByDefaultModelDescDisplayNameAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public List<AiModelConfigDTO> listAllModels() {
        return repository.findAllByOrderByBaseUrlAscDisplayNameAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public AiModelConfig resolveModel(UUID modelConfigId) {
        if (modelConfigId != null) {
            AiModelConfig model = repository.findById(modelConfigId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "AI model config does not exist"
                    ));
            if (!model.isEnabled()) {
                throw new IllegalArgumentException("AI model config is disabled");
            }
            return model;
        }
        return repository.findFirstByEnabledTrueOrderByDefaultModelDescDisplayNameAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled AI model config is available"
                ));
    }

    @Transactional
    public AiModelConfigDTO createModel(AiModelConfigRequestDTO request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String modelName = requireText(request.modelName(), "Model name");
        String displayName = requireText(request.displayName(), "Display name");
        String credential = request.credential();
        AiModelProviderType providerType = request.providerType() == null
                ? AiModelProviderType.OPENAI_COMPATIBLE_LOCAL
                : request.providerType();

        AiModelConfig model = repository.findByBaseUrlAndModelName(baseUrl, modelName)
                .orElseGet(AiModelConfig::new);
        boolean newModel = model.getId() == null;
        model.setDisplayName(displayName);
        model.setProviderType(providerType);
        model.setBaseUrl(baseUrl);
        model.setModelName(modelName);
        // null credential = leave unchanged (except on create); "" = clear.
        if (credential != null || newModel) {
            model.setCredentialEncrypted(secretCipher.encrypt(credential));
        }
        model.setEnabled(true);
        model.setDefaultModel(request.defaultModel());

        if (request.defaultModel()) {
            repository.findAll().forEach(existing -> {
                if (existing.getId() == null || !existing.getId().equals(model.getId())) {
                    existing.setDefaultModel(false);
                }
            });
        }

        return toDto(repository.save(model));
    }

    @Transactional
    public List<AiModelConfigDTO> discoverAndOnboardModels(
            String requestedBaseUrl,
            String requestedCredential,
            AiModelProviderType requestedProviderType
    ) {
        String baseUrl = normalizeBaseUrl(requestedBaseUrl);
        AiModelConfig existingEndpoint = repository
                .findFirstByBaseUrlOrderByCreatedAtAsc(baseUrl)
                .orElse(null);
        // A provided credential is encrypted and stored on discovered models; when
        // omitted, reuse the endpoint's existing encrypted credential as-is.
        String providedCredential = optionalText(requestedCredential);
        String encryptedToStore = providedCredential != null
                ? secretCipher.encrypt(providedCredential)
                : existingEndpoint != null ? existingEndpoint.getCredentialEncrypted() : null;
        String credential = providedCredential != null
                ? providedCredential
                : secretCipher.decrypt(encryptedToStore);
        AiModelProviderType providerType = requestedProviderType != null
                ? requestedProviderType
                : existingEndpoint == null
                        ? AiModelProviderType.OPENAI_COMPATIBLE_LOCAL
                        : existingEndpoint.getProviderType();

        HttpRequest.Builder modelsRequestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models"))
                .timeout(Duration.ofSeconds(10))
                .GET();
        applyAuthorization(modelsRequestBuilder, credential);
        HttpRequest modelsRequest = modelsRequestBuilder.build();

        String responseBody;
        int statusCode;
        try {
            HttpResponse<String> response = httpClient.send(
                    modelsRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            statusCode = response.statusCode();
            responseBody = response.body();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Could not reach model endpoint: " + ex.getMessage(), ex
            );
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(
                    "Model endpoint returned HTTP " + statusCode + " for /models"
            );
        }

        List<String> modelIds = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                throw new IllegalStateException(
                        "No models found at " + baseUrl + "/models"
                );
            }
            for (JsonNode item : data) {
                JsonNode idNode = item.get("id");
                if (idNode != null && !idNode.isNull() && !idNode.asText().isBlank()) {
                    String modelId = idNode.asText().trim();
                    if (!modelIds.contains(modelId)) {
                        modelIds.add(modelId);
                    }
                }
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Could not parse /models response: " + ex.getMessage(), ex
            );
        }

        if (modelIds.isEmpty()) {
            throw new IllegalStateException(
                    "No models found at " + baseUrl + "/models"
            );
        }

        boolean noExistingModels = repository.countByEnabledTrue() == 0;
        List<AiModelConfigDTO> results = new ArrayList<>();

        for (int i = 0; i < modelIds.size(); i++) {
            String modelId = modelIds.get(i);
            AiModelConfig model = repository.findByBaseUrlAndModelName(baseUrl, modelId)
                    .orElseGet(AiModelConfig::new);
            boolean newModel = model.getId() == null;
            model.setDisplayName(modelId);
            model.setProviderType(providerType);
            model.setBaseUrl(baseUrl);
            model.setModelName(modelId);
            if (providedCredential != null || newModel) {
                model.setCredentialEncrypted(encryptedToStore);
            }
            model.setEnabled(true);
            if (noExistingModels && i == 0) {
                model.setDefaultModel(true);
            } else if (newModel) {
                model.setDefaultModel(false);
            }
            results.add(toDto(repository.save(model)));
        }

        ensureDefaultModel();
        return results;
    }

    @Transactional
    public AiModelConfigDTO setModelEnabled(UUID modelId, boolean enabled) {
        AiModelConfig model = repository.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AI model config does not exist"
                ));
        model.setEnabled(enabled);
        if (!enabled) {
            model.setDefaultModel(false);
        }
        AiModelConfig saved = repository.save(model);
        ensureDefaultModel();
        return toDto(saved);
    }

    @Transactional
    public void deleteModel(UUID modelId) {
        AiModelConfig model = repository.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AI model config does not exist"
                ));
        boolean wasDefault = model.isDefaultModel();
        repository.delete(model);
        if (wasDefault) {
            ensureDefaultModel();
        }
    }

    public AiModelTestResponseDTO testLocalModel(AiModelTestRequestDTO request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String modelName = optionalText(request.modelName());

        try {
            String credential = optionalText(request.credential());
            if (credential == null) {
                credential = repository.findFirstByBaseUrlOrderByCreatedAtAsc(baseUrl)
                        .map(AiModelConfig::getCredentialEncrypted)
                        .map(secretCipher::decrypt)
                        .orElse(null);
            }
            HttpRequest.Builder modelsRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            applyAuthorization(modelsRequestBuilder, credential);
            HttpRequest modelsRequest = modelsRequestBuilder.build();
            HttpResponse<String> modelsResponse = httpClient.send(
                    modelsRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (modelsResponse.statusCode() < 200 || modelsResponse.statusCode() >= 300) {
                return new AiModelTestResponseDTO(
                        false,
                        "Model endpoint returned " + modelsResponse.statusCode()
                                + " for /models"
                );
            }

            int modelCount = modelCount(modelsResponse.body());
            if (modelName == null) {
                return new AiModelTestResponseDTO(
                        true,
                        "Found " + modelCount + " model" + (modelCount == 1 ? "" : "s")
                );
            }

            HttpRequest.Builder chatRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"%s","messages":[{"role":"user","content":"Reply with pong."}],"max_tokens":16,"stream":false}
                            """.formatted(escapeJson(modelName))));
            applyAuthorization(chatRequestBuilder, credential);
            HttpRequest chatRequest = chatRequestBuilder.build();
            HttpResponse<String> chatResponse = httpClient.send(
                    chatRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (chatResponse.statusCode() < 200 || chatResponse.statusCode() >= 300) {
                return new AiModelTestResponseDTO(
                        false,
                        "Chat completion returned " + chatResponse.statusCode()
                );
            }

            return new AiModelTestResponseDTO(true, "Model endpoint is reachable");
        } catch (Exception exception) {
            return new AiModelTestResponseDTO(
                    false,
                    "Could not reach model endpoint: " + exception.getMessage()
            );
        }
    }

    private void ensureDefaultModel() {
        List<AiModelConfig> enabledModels = repository.findByEnabledTrueOrderByDefaultModelDescDisplayNameAsc();
        if (enabledModels.isEmpty() || enabledModels.stream().anyMatch(AiModelConfig::isDefaultModel)) {
            return;
        }
        AiModelConfig nextDefault = enabledModels.get(0);
        nextDefault.setDefaultModel(true);
        repository.save(nextDefault);
    }

    private AiModelConfigDTO toDto(AiModelConfig model) {
        return new AiModelConfigDTO(
                model.getId(),
                model.getDisplayName(),
                model.getProviderType(),
                model.getBaseUrl(),
                model.getModelName(),
                model.isEnabled(),
                model.isDefaultModel(),
                model.getCredentialEncrypted() != null,
                model.getStructuredOutputMode()
        );
    }

    private String normalizeBaseUrl(String value) {
        String baseUrl = requireText(value, "Base URL");
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if ((scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Base URL must be a valid HTTP or HTTPS endpoint"
            );
        }
        return baseUrl;
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value.trim();
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void applyAuthorization(HttpRequest.Builder builder, String credential) {
        if (credential != null) {
            builder.header("Authorization", "Bearer " + credential);
        }
    }

    private int modelCount(String responseBody) {
        try {
            JsonNode data = objectMapper.readTree(responseBody).get("data");
            return data != null && data.isArray() ? data.size() : 0;
        } catch (Exception exception) {
            return 0;
        }
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
