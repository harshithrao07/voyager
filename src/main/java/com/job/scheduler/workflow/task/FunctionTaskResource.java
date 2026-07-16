package com.job.scheduler.workflow.task;

import com.job.scheduler.service.FunctionInvocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class FunctionTaskResource implements TaskResource {
    /** The {@code function} category under the {@code voyager} scheme. */
    private static final String CATEGORY = "function";

    private final FunctionInvocationService functionInvocationService;

    @Override
    public boolean supports(URI resource) {
        return "voyager".equals(resource.getScheme())
                && CATEGORY.equals(resource.getHost());
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments) {
        return execute(resource, arguments, TaskExecutionContext.NONE);
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments, TaskExecutionContext context) {
        FunctionResourceRef ref;
        try {
            ref = parseFunctionResource(resource);
        } catch (IllegalArgumentException exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED, exception.getMessage(), exception);
        }
        if (ref == null) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    "Function Task resource must be voyager://function/name@version"
            );
        }
        return functionInvocationService.invokeForTask(
                ref.name(),
                ref.version(),
                arguments,
                context
        );
    }

    /**
     * Parses a {@code voyager://function/name[@version]} resource. Returns
     * {@code null} for any non-function resource. Throws
     * {@link IllegalArgumentException} with a human-readable message when the
     * resource is a function resource but malformed. Shared by execution and
     * save-time validation so the two can never disagree.
     */
    public static FunctionResourceRef parseFunctionResource(String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(resource);
        } catch (IllegalArgumentException exception) {
            // A syntactically invalid URI only matters when it looks like ours.
            if (resource.startsWith("voyager://" + CATEGORY)) {
                throw new IllegalArgumentException(
                        "Function Task resource must be voyager://function/name@version");
            }
            return null;
        }
        return parseFunctionResource(uri);
    }

    public static FunctionResourceRef parseFunctionResource(URI uri) {
        if (uri == null
                || !"voyager".equals(uri.getScheme())
                || !CATEGORY.equals(uri.getHost())) {
            return null;
        }
        String path = trimPathSlashes(uri.getPath());
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    "Function Task resource must be voyager://function/name@version");
        }

        String name = path;
        Integer version = null;
        int versionMarker = name.lastIndexOf('@');
        if (versionMarker >= 0) {
            version = parseVersion(name.substring(versionMarker + 1));
            name = name.substring(0, versionMarker);
        }

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "Function Task resource must include a function name");
        }
        return new FunctionResourceRef(name, version);
    }

    private static Integer parseVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()
                || "latest".equalsIgnoreCase(rawVersion)) {
            return null;
        }
        String normalized = rawVersion.startsWith("v")
                || rawVersion.startsWith("V")
                ? rawVersion.substring(1)
                : rawVersion;
        try {
            int parsed = Integer.parseInt(normalized);
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive version");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Function version must be latest, vN, or N");
        }
    }

    private static String trimPathSlashes(String value) {
        return value == null ? null : value.replaceAll("^/+|/+$", "");
    }

    /**
     * Parsed components of a {@code voyager://function/...} Task resource.
     * A {@code null} version means "the function's active version at run time".
     */
    public record FunctionResourceRef(
            String name,
            Integer version
    ) {
    }
}
