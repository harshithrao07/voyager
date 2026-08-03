package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.WebhookPayload;
import com.job.scheduler.handlers.WebhookHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task resource for {@code voyager://system/webhook}. Maps HTTP outcomes to the
 * stable webhook error vocabulary and preserves the status and (truncated)
 * response body as structured detail.
 */
@Component
@RequiredArgsConstructor
public class SchedulerWebhookTaskResource implements TaskResource {
    /** Keep the persisted error detail well under the error-details limit. */
    private static final int MAX_BODY_CHARS = 2048;

    private final WebhookHandler webhookHandler;
    private final TaskPayloadMapper payloadMapper;

    @Override
    public boolean supports(URI resource) {
        return "voyager".equals(resource.getScheme())
                && "system".equals(resource.getHost())
                && "webhook".equals(trimSlashes(resource.getPath()));
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments) {
        return execute(resource, arguments, TaskExecutionContext.NONE);
    }

    @Override
    public JsonNode execute(
            URI resource,
            JsonNode arguments,
            TaskExecutionContext context
    ) {
        WebhookPayload payload =
                payloadMapper.bind(arguments, WebhookPayload.class);
        if (Boolean.TRUE.equals(payload.includeExecutionContextHeaders())) {
            payload = withExecutionContextHeaders(payload, context);
        }
        try {
            StepResult result = webhookHandler.handle(payload);
            return TaskResourceOutput.of(result);
        } catch (HttpStatusCodeException exception) {
            throw statusError(exception);
        } catch (ResourceAccessException exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.WEBHOOK_TIMEOUT,
                    "Webhook could not be reached: " + exception.getMessage(),
                    exception
            );
        } catch (IllegalStateException exception) {
            // Defensive: the handler's own non-2xx guard. RestClient normally
            // raises HttpStatusCodeException before this is reached.
            throw new TaskResourceException(
                    TaskResourceErrors.WEBHOOK_SERVER_ERROR,
                    exception.getMessage(),
                    exception
            );
        }
    }

    private WebhookPayload withExecutionContextHeaders(
            WebhookPayload payload,
            TaskExecutionContext context
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (payload.headers() != null) {
            headers.putAll(payload.headers());
        }
        if (context != null) {
            putUuid(headers, "X-Voyager-Workflow-Execution-Id",
                    context.workflowExecutionId());
            putUuid(headers, "X-Voyager-State-Execution-Attempt-Id",
                    context.stateExecutionAttemptId());
            if (context.stateName() != null) {
                headers.put("X-Voyager-State-Name", context.stateName());
            }
        }
        return new WebhookPayload(
                payload.url(),
                payload.method(),
                Map.copyOf(headers),
                payload.body(),
                payload.includeExecutionContextHeaders()
        );
    }

    private void putUuid(
            Map<String, String> headers,
            String name,
            java.util.UUID value
    ) {
        if (value != null) {
            headers.put(name, value.toString());
        }
    }

    private TaskResourceException statusError(HttpStatusCodeException exception) {
        HttpStatusCode status = exception.getStatusCode();
        ObjectNode detail = JsonNodeFactory.instance.objectNode();
        detail.put("statusCode", status.value());
        detail.put("body", truncate(exception.getResponseBodyAsString()));

        String error;
        if (status.value() == 401 || status.value() == 403) {
            error = TaskResourceErrors.PERMISSIONS;
        } else if (status.is4xxClientError()) {
            error = TaskResourceErrors.WEBHOOK_CLIENT_ERROR;
        } else {
            error = TaskResourceErrors.WEBHOOK_SERVER_ERROR;
        }
        return new TaskResourceException(
                error,
                "Webhook returned HTTP " + status.value(),
                detail
        );
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY_CHARS
                ? body
                : body.substring(0, MAX_BODY_CHARS);
    }
}
