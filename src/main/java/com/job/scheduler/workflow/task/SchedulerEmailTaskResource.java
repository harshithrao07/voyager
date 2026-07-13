package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.SendEmailPayload;
import com.job.scheduler.handlers.SendEmailHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** Task resource for {@code voyager://send-email}. */
@Component
@RequiredArgsConstructor
public class SchedulerEmailTaskResource implements TaskResource {
    private final SendEmailHandler sendEmailHandler;
    private final TaskPayloadMapper payloadMapper;

    @Override
    public boolean supports(URI resource) {
        return "voyager".equals(resource.getScheme())
                && "send-email".equals(operation(resource));
    }

    @Override
    public JsonNode execute(URI resource, JsonNode arguments) {
        SendEmailPayload payload =
                payloadMapper.bind(arguments, SendEmailPayload.class);
        try {
            StepResult result = sendEmailHandler.handle(payload);
            return TaskResourceOutput.of(result);
        } catch (MailException exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.EMAIL_SEND_FAILED,
                    "Email send failed: " + exception.getMessage(),
                    exception
            );
        }
    }
}
