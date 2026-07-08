package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.Judge0LimitsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class Judge0Client {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // Judge0 language ids are stable per instance; cache id -> name to avoid a
    // /languages round-trip on every multi-file submission.
    private final Map<Integer, String> languageNameCache = new ConcurrentHashMap<>();

    @Value("${scheduler.judge0.base-url:http://localhost:2358}")
    private String baseUrl;

    @Value("${scheduler.judge0.auth-token:}")
    private String authToken;

    @Value("${scheduler.judge0.enable-per-process-and-thread-time-limit:true}")
    private boolean enablePerProcessAndThreadTimeLimit;

    @Value("${scheduler.judge0.enable-per-process-and-thread-memory-limit:true}")
    private boolean enablePerProcessAndThreadMemoryLimit;

    public List<FunctionLanguageDTO> listLanguages() {
        String response = restClient.get()
                .uri(url("/languages"))
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
        JsonNode root = readTree(response);
        List<FunctionLanguageDTO> languages = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return languages;
        }
        for (JsonNode language : root) {
            JsonNode id = language.get("id");
            JsonNode name = language.get("name");
            if (id != null && id.isIntegralNumber()
                    && name != null && name.isString()) {
                languages.add(new FunctionLanguageDTO(
                        id.intValue(),
                        name.stringValue()
                ));
            }
        }
        return languages;
    }

    /**
     * Resolves a Judge0 language id to its display name (e.g. "Python (3.8.1)"),
     * caching the full language list on first use. Returns {@code null} if the id
     * is unknown or Judge0 is unreachable.
     */
    public String languageName(int languageId) {
        String cached = languageNameCache.get(languageId);
        if (cached != null) {
            return cached;
        }
        for (FunctionLanguageDTO language : listLanguages()) {
            languageNameCache.put(language.id(), language.name());
        }
        return languageNameCache.get(languageId);
    }

    public String createSubmission(Judge0SubmissionRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("language_id", request.languageId());
        putText(body, "source_code", request.sourceCode());
        putText(body, "additional_files", request.additionalFilesBase64());
        putText(body, "stdin", request.stdin());
        putText(body, "compiler_options", request.compilerOptions());
        putText(body, "command_line_arguments", request.commandLineArguments());
        body.put("cpu_time_limit", request.cpuTimeLimitSeconds());
        body.put("wall_time_limit", request.wallTimeLimitSeconds());
        body.put("memory_limit", request.memoryLimitKb());
        body.put("max_file_size", request.maxFileSizeKb());
        body.put("enable_network", request.enableNetwork());
        body.put("enable_per_process_and_thread_time_limit", enablePerProcessAndThreadTimeLimit);
        body.put("enable_per_process_and_thread_memory_limit", enablePerProcessAndThreadMemoryLimit);
        body.put("redirect_stderr_to_stdout", false);

        String response = restClient.post()
                .uri(url("/submissions/?base64_encoded=false&wait=false"))
                .headers(headers -> {
                    applyAuth(headers);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(body.toString())
                .retrieve()
                .body(String.class);
        JsonNode root = readTree(response);
        JsonNode token = root == null ? null : root.get("token");
        if (token == null || !token.isString()) {
            throw new IllegalStateException("Judge0 did not return a submission token");
        }
        return token.stringValue();
    }

    public Judge0SubmissionResult getSubmission(String token) {
        String response = restClient.get()
                .uri(url("/submissions/" + token + "?base64_encoded=false"))
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
        JsonNode root = readTree(response);
        JsonNode status = root == null ? null : root.get("status");
        return new Judge0SubmissionResult(
                text(root, "token"),
                intValue(status, "id"),
                text(status, "description"),
                text(root, "stdout"),
                text(root, "stderr"),
                text(root, "compile_output"),
                text(root, "message"),
                intValue(root, "exit_code"),
                intValue(root, "exit_signal"),
                doubleValue(root, "time"),
                doubleValue(root, "wall_time"),
                longValue(root, "memory")
        );
    }

    public int countStatuses() {
        String response = restClient.get()
                .uri(url("/statuses"))
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
        JsonNode root = readTree(response);
        return root != null && root.isArray() ? root.size() : 0;
    }

    public Judge0LimitsDTO configInfo() {
        String response = restClient.get()
                .uri(url("/config_info"))
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
        JsonNode root = readTree(response);
        return new Judge0LimitsDTO(
                doubleValue(root, "cpu_time_limit"),
                doubleValue(root, "max_cpu_time_limit"),
                doubleValue(root, "wall_time_limit"),
                doubleValue(root, "max_wall_time_limit"),
                longValue(root, "memory_limit"),
                longValue(root, "max_memory_limit"),
                intValue(root, "max_file_size"),
                intValue(root, "max_extract_size"),
                boolValue(root, "enable_network"),
                boolValue(root, "allow_enable_network")
        );
    }

    public WorkerStats workerStats() {
        String response = restClient.get()
                .uri(url("/workers"))
                .headers(this::applyAuth)
                .retrieve()
                .body(String.class);
        JsonNode root = readTree(response);
        int total = 0;
        int available = 0;
        if (root != null && root.isArray()) {
            for (JsonNode pool : root) {
                total += intOrZero(pool, "size");
                available += intOrZero(pool, "available");
            }
        }
        return new WorkerStats(total, available);
    }

    public record WorkerStats(int total, int available) {
    }

    private void applyAuth(HttpHeaders headers) {
        if (authToken != null && !authToken.isBlank()) {
            headers.set("X-Auth-Token", authToken);
        }
    }

    private void putText(ObjectNode body, String name, String value) {
        if (value != null) {
            body.put(name, value);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return value == null ? objectMapper.createObjectNode()
                    : objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Judge0 returned invalid JSON", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            String text = value.stringValue();
            return text.isBlank() ? null : Integer.valueOf(text);
        }
        return value.intValue();
    }

    private int intOrZero(JsonNode node, String field) {
        Integer value = intValue(node, field);
        return value == null ? 0 : value;
    }

    private Boolean boolValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isString()) {
            String text = value.stringValue().trim();
            return text.isBlank() ? null : Boolean.valueOf(text);
        }
        if (value.isIntegralNumber()) {
            return value.intValue() != 0;
        }
        return null;
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            String text = value.stringValue();
            return text.isBlank() ? null : Long.valueOf(text);
        }
        return value.longValue();
    }

    private Double doubleValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            String text = value.stringValue();
            return text.isBlank() ? null : Double.valueOf(text);
        }
        return value.doubleValue();
    }

    private String url(String path) {
        String trimmedBase = baseUrl == null || baseUrl.isBlank()
                ? "http://localhost:2358"
                : baseUrl;
        while (trimmedBase.endsWith("/")) {
            trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
        }
        return trimmedBase + path;
    }
}
