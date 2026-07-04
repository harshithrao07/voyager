package com.job.scheduler.workflow.task;

import com.job.scheduler.service.FunctionInvocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class FunctionTaskResource implements TaskResource {
    private final FunctionInvocationService functionInvocationService;

    @Override
    public boolean supports(URI resource) {
        return "function".equals(resource.getScheme());
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments) {
        return execute(resource, arguments, TaskExecutionContext.NONE);
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments, TaskExecutionContext context) {
        FunctionResourceRef ref = parse(resource);
        return functionInvocationService.invokeForTask(
                ref.namespace(),
                ref.name(),
                ref.version(),
                arguments,
                context
        );
    }

    private FunctionResourceRef parse(URI resource) {
        String namespace = resource.getHost();
        String path = trimSlashes(resource.getPath());
        if (namespace == null || namespace.isBlank()
                || path == null || path.isBlank()) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    "Function Task resource must be function://namespace/name@version"
            );
        }

        String name = path;
        Integer version = null;
        int versionMarker = path.lastIndexOf('@');
        if (versionMarker >= 0) {
            name = path.substring(0, versionMarker);
            version = parseVersion(path.substring(versionMarker + 1));
        }

        if (name.isBlank()) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    "Function Task resource must include a function name"
            );
        }
        return new FunctionResourceRef(namespace, name, version);
    }

    private Integer parseVersion(String rawVersion) {
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
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    "Function version must be latest, vN, or N"
            );
        }
    }

    private record FunctionResourceRef(
            String namespace,
            String name,
            Integer version
    ) {
    }
}
