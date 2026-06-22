package com.job.scheduler.workflow.task;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Binds Task arguments to a typed payload and applies bean validation. A shape
 * or constraint mismatch is a workflow-authoring error, so it is classified as
 * {@link TaskResourceErrors#TASK_FAILED} rather than a resource failure.
 */
@Component
@RequiredArgsConstructor
public class TaskPayloadMapper {
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public <T> T bind(JsonNode arguments, Class<T> type) {
        T payload;
        try {
            payload = objectMapper.treeToValue(arguments, type);
        } catch (Exception exception) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    "Task arguments do not match " + type.getSimpleName(),
                    exception
            );
        }
        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            throw new TaskResourceException(
                    TaskResourceErrors.TASK_FAILED,
                    "Task arguments are invalid: " + describe(violations)
            );
        }
        return payload;
    }

    private <T> String describe(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath()
                        + " " + violation.getMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }
}
