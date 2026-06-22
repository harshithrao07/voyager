package com.job.scheduler.workflow.task;

import com.job.scheduler.dto.payload.SendEmailPayload;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskPayloadMapperTest {
    @Mock
    private Validator validator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TaskPayloadMapper mapper() {
        return new TaskPayloadMapper(objectMapper, validator);
    }

    @Test
    void bindsValidArguments() {
        when(validator.validate(any())).thenReturn(Set.of());

        SendEmailPayload payload = mapper().bind(
                objectMapper.createObjectNode()
                        .put("to", "a@b.c")
                        .put("subject", "s")
                        .put("body", "b"),
                SendEmailPayload.class);

        assertThat(payload.to()).isEqualTo("a@b.c");
    }

    @Test
    void classifiesShapeMismatchAsTaskFailed() {
        assertThatThrownBy(() -> mapper().bind(
                objectMapper.createArrayNode().add("wrong"),
                SendEmailPayload.class))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.TASK_FAILED);
    }

    @Test
    void classifiesValidationFailureAsTaskFailed() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<SendEmailPayload> violation =
                org.mockito.Mockito.mock(ConstraintViolation.class);
        doReturn(Set.of(violation)).when(validator).validate(any());

        assertThatThrownBy(() -> mapper().bind(
                objectMapper.createObjectNode()
                        .put("to", "").put("subject", "").put("body", ""),
                SendEmailPayload.class))
                .isInstanceOf(TaskResourceException.class)
                .extracting(e -> ((TaskResourceException) e).error())
                .isEqualTo(TaskResourceErrors.TASK_FAILED);
    }
}
