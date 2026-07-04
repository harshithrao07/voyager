package com.job.scheduler.workflow.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;

/**
 * Routes a Task resource URI to the first registered {@link TaskResource} that
 * supports it. Resources are discovered as Spring beans, so new integrations are
 * added without modifying this class.
 */
@Service
@RequiredArgsConstructor
public class TaskResourceRouter {
    private final List<TaskResource> resources;

    public JsonNode execute(String resource, JsonNode arguments) {
        return execute(resource, arguments, TaskExecutionContext.NONE);
    }

    public JsonNode execute(
            String resource,
            JsonNode arguments,
            TaskExecutionContext context
    ) {
        URI uri = URI.create(resource);
        return resources.stream()
                .filter(candidate -> candidate.supports(uri))
                .findFirst()
                .orElseThrow(() -> new TaskResourceException(
                        TaskResourceErrors.TASK_FAILED,
                        "Unsupported Task resource: " + resource
                ))
                .execute(uri, arguments, context);
    }
}
