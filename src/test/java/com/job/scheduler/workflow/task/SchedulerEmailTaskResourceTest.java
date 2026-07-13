package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.StepResult;
import com.job.scheduler.dto.payload.SendEmailPayload;
import com.job.scheduler.handlers.SendEmailHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerEmailTaskResourceTest {
    @Mock
    private SendEmailHandler sendEmailHandler;
    @Mock
    private TaskPayloadMapper payloadMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SchedulerEmailTaskResource resource;
    private final URI uri = URI.create("voyager://send-email");

    @BeforeEach
    void setUp() {
        resource = new SchedulerEmailTaskResource(
                sendEmailHandler, payloadMapper);
        org.mockito.Mockito.lenient().when(payloadMapper.bind(any(), any()))
                .thenReturn(new SendEmailPayload("a@b.c", "s", "b"));
    }

    @Test
    void supportsVoyagerSendEmailOnly() {
        assertThat(resource.supports(uri)).isTrue();
        assertThat(resource.supports(URI.create("voyager://webhook")))
                .isFalse();
    }

    @Test
    void returnsOutputOnSuccess() {
        when(sendEmailHandler.handle(any())).thenReturn(StepResult.empty());

        assertThat(resource.execute(uri, objectMapper.createObjectNode())
                .isObject()).isTrue();
    }

    @Test
    void mapsMailFailureToSendFailed() {
        when(sendEmailHandler.handle(any()))
                .thenThrow(new MailSendException("smtp down"));

        assertThatThrownBy(() ->
                resource.execute(uri, objectMapper.createObjectNode()))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.EMAIL_SEND_FAILED);
    }
}
