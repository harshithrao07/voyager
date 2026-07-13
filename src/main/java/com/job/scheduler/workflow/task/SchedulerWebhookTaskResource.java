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

/**
 * Task resource for {@code voyager://webhook}. Maps HTTP outcomes to the
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
                && "webhook".equals(operation(resource));
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments) {
        WebhookPayload payload =
                payloadMapper.bind(arguments, WebhookPayload.class);
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
