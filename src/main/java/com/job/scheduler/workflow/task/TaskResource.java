package com.job.scheduler.workflow.task;

import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * Extension point for a Task resource integration. Each resource is a Spring
 * bean; {@link TaskResourceRouter} selects the first one whose {@link #supports}
 * returns true for a resource URI. New resources plug in by adding a bean — the
 * router is not edited.
 *
 * <p>A resource maps its failures to a stable ASL error name (see
 * {@link TaskResourceErrors}) by throwing {@link TaskResourceException}, so that
 * workflow authors can match them in {@code Retry}/{@code Catch.ErrorEquals}
 * without depending on implementation classes.
 */
public interface TaskResource {

    /** Whether this resource handles the given resource URI. */
    boolean supports(URI resource);

    /**
     * Executes the resource and returns its output. Implementations should
     * translate every failure into a {@link TaskResourceException} carrying a
     * stable error name and, where useful, structured detail.
     */
    JsonNode execute(URI resource, JsonNode arguments);

    /**
     * Executes with the workflow {@link TaskExecutionContext}. Resources that
     * record per-run detail (e.g. function invocations) override this; the rest
     * fall back to {@link #execute(URI, JsonNode)} and ignore the context.
     */
    default JsonNode execute(URI resource, JsonNode arguments, TaskExecutionContext context) {
        return execute(resource, arguments);
    }

    /** The resource operation: the URI host, or its trimmed path when hostless. */
    default String operation(URI resource) {
        String host = resource.getHost();
        if (host != null && !host.isBlank()) {
            return host;
        }
        return trimSlashes(resource.getPath());
    }

    /** Strips leading and trailing slashes; null-safe. */
    default String trimSlashes(String value) {
        return value == null ? null : value.replaceAll("^/+|/+$", "");
    }
}
