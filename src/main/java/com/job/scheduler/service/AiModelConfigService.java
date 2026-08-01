package com.job.scheduler.service;

import com.job.scheduler.dto.AiModelConfigRequestDTO;
import com.job.scheduler.dto.AiModelTestRequestDTO;
import com.job.scheduler.dto.AiModelTestResponseDTO;
import com.job.scheduler.dto.AiModelConfigDTO;
import com.job.scheduler.entity.AiModelConfig;
import com.job.scheduler.enums.AiModelProviderType;
import com.job.scheduler.enums.AiModelRole;
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
import java.util.Optional;
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

    /** Enabled CHAT models — the workflow-generation / judge picker. */
    @Transactional
    public List<AiModelConfigDTO> listEnabledModels() {
        return repository
                .findByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(AiModelRole.CHAT)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /** Resolves the default enabled EMBEDDING model, or empty when none is registered. */
    @Transactional
    public Optional<AiModelConfig> findDefaultEmbeddingModel() {
        return repository
                .findFirstByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(
                        AiModelRole.EMBEDDING
                );
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
        return repository
                .findFirstByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(
                        AiModelRole.CHAT
                )
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
        AiModelRole role = request.role() == null ? AiModelRole.CHAT : request.role();

        AiModelConfig model = repository.findByBaseUrlAndModelName(baseUrl, modelName)
                .orElseGet(AiModelConfig::new);
        boolean newModel = model.getId() == null;
        model.setDisplayName(displayName);
        model.setProviderType(providerType);
        model.setRole(role);
        model.setBaseUrl(baseUrl);
        model.setModelName(modelName);
        // null credential = leave unchanged (except on create); "" = clear.
        if (credential != null || newModel) {
            model.setCredentialEncrypted(secretCipher.encrypt(credential));
        }
        model.setEnabled(true);
        model.setDefaultModel(request.defaultModel());

        // default_model is scoped per role: registering a default only demotes peers
        // of the same role, so the chat and embedding defaults are independent.
        if (request.defaultModel()) {
            repository.findAll().forEach(existing -> {
                if (existing.getRole() == role
                        && (existing.getId() == null
                        || !existing.getId().equals(model.getId()))) {
                    existing.setDefaultModel(false);
                }
            });
        }

        AiModelConfig saved = repository.save(model);
        ensureDefaultModel();
        return toDto(saved);
    }

    @Transactional
    public List<AiModelConfigDTO> discoverAndOnboardModels(
            String requestedBaseUrl,
            String requestedCredential,
            AiModelProviderType requestedProviderType,
            AiModelRole requestedRole
    ) {
        String baseUrl = normalizeBaseUrl(requestedBaseUrl);
        AiModelRole role = requestedRole == null ? AiModelRole.CHAT : requestedRole;
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

        boolean noExistingModels = repository.countByEnabledTrueAndRole(role) == 0;
        List<AiModelConfigDTO> results = new ArrayList<>();

        for (int i = 0; i < modelIds.size(); i++) {
            String modelId = modelIds.get(i);
            AiModelConfig model = repository.findByBaseUrlAndModelName(baseUrl, modelId)
                    .orElseGet(AiModelConfig::new);
            boolean newModel = model.getId() == null;
            model.setDisplayName(modelId);
            model.setProviderType(providerType);
            model.setRole(role);
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

    /** Promotes a model to the default for its role, demoting its same-role peers. */
    @Transactional
    public AiModelConfigDTO setDefaultModel(UUID modelId) {
        AiModelConfig model = repository.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AI model config does not exist"
                ));
        if (!model.isEnabled()) {
            throw new IllegalArgumentException("Enable the model before making it the default");
        }
        AiModelRole role = model.getRole();
        // default_model is per-role: only same-role peers are demoted.
        repository.findAll().forEach(existing -> {
            if (existing.getRole() == role) {
                existing.setDefaultModel(existing.getId().equals(modelId));
            }
        });
        return toDto(model);
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

            // Embedding models expose /embeddings, not /chat/completions; probe the
            // endpoint that actually serves the model's role.
            if (request.role() == AiModelRole.EMBEDDING) {
                HttpRequest.Builder embeddingRequestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/embeddings"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"model":"%s","input":"ping"}
                                """.formatted(escapeJson(modelName))));
                applyAuthorization(embeddingRequestBuilder, credential);
                HttpResponse<String> embeddingResponse = httpClient.send(
                        embeddingRequestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (embeddingResponse.statusCode() < 200
                        || embeddingResponse.statusCode() >= 300) {
                    return new AiModelTestResponseDTO(
                            false,
                            "Embeddings request returned " + embeddingResponse.statusCode()
                    );
                }
                return new AiModelTestResponseDTO(true, "Embedding endpoint is reachable");
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

    /** Each role keeps its own default; backfill one where a role has enabled models but no default. */
    private void ensureDefaultModel() {
        for (AiModelRole role : AiModelRole.values()) {
            ensureDefaultModel(role);
        }
    }

    private void ensureDefaultModel(AiModelRole role) {
        List<AiModelConfig> enabledModels = repository
                .findByEnabledTrueAndRoleOrderByDefaultModelDescDisplayNameAsc(role);
        if (enabledModels.isEmpty()
                || enabledModels.stream().anyMatch(AiModelConfig::isDefaultModel)) {
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
                model.getRole(),
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
