package com.job.scheduler.exception;

import com.job.scheduler.dto.ApiErrorDTO;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidationException;
import com.job.scheduler.workflow.asl.validation.AslValidationCategory;
import com.job.scheduler.workflow.asl.validation.AslValidationIssue;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/app/v1/workflows");

    @Test
    void mapsAslValidationIssuesToFieldErrors() {
        var exception = new AslDefinitionValidationException(List.of(
                new AslValidationIssue(
                        "$.States.Call",
                        AslValidationCategory.ASL,
                        "MISSING_RESOURCE",
                        "Task Resource is required"
                )
        ));

        var response =
                handler.handleAslDefinitionValidation(exception, request);

        assertResponse(
                response.getBody(),
                HttpStatus.BAD_REQUEST,
                "ASL_VALIDATION_ERROR"
        );
        assertThat(response.getBody().fieldErrors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.field()).isEqualTo("$.States.Call");
                    assertThat(error.message())
                            .contains("MISSING_RESOURCE")
                            .contains("Task Resource is required");
                });
    }

    @Test
    void mapsRequestBindingErrors() {
        RequestModel target = new RequestModel();
        BeanPropertyBindingResult binding =
                new BeanPropertyBindingResult(target, "request");
        binding.rejectValue("name", "required", "Name is required");

        var response = handler.handleRequestValidation(
                new MethodArgumentNotValidException(null, binding),
                request
        );

        assertResponse(
                response.getBody(),
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR"
        );
        assertThat(response.getBody().fieldErrors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.field()).isEqualTo("name");
                    assertThat(error.message()).isEqualTo("Name is required");
                });
    }

    @Test
    void mapsConstraintViolations() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation =
                mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("startExecution.input");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be null");

        var response = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)),
                request
        );

        assertResponse(
                response.getBody(),
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR"
        );
        assertThat(response.getBody().fieldErrors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.field())
                            .isEqualTo("startExecution.input");
                    assertThat(error.message()).isEqualTo("must not be null");
                });
    }

    @Test
    void mapsBadRequestNotFoundConflictAndVersionConflict() {
        assertResponse(
                handler.handleBadRequest(
                        new IllegalArgumentException("bad input"),
                        request
                ).getBody(),
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST"
        );
        assertResponse(
                handler.handleNotFound(
                        new EntityNotFoundException("missing"),
                        request
                ).getBody(),
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND"
        );
        assertResponse(
                handler.handleConflict(
                        new IllegalStateException("wrong state"),
                        request
                ).getBody(),
                HttpStatus.CONFLICT,
                "INVALID_STATE"
        );
        ApiErrorDTO version = handler.handleOptimisticLock(
                new OptimisticLockingFailureException("stale"),
                request
        ).getBody();
        assertResponse(version, HttpStatus.CONFLICT, "VERSION_CONFLICT");
        assertThat(version.message()).contains("modified by another request");
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        ApiErrorDTO body = handler.handleUnexpected(
                new RuntimeException("database password leaked"),
                request
        ).getBody();

        assertResponse(
                body,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR"
        );
        assertThat(body.message())
                .isEqualTo("An unexpected error occurred")
                .doesNotContain("password");
    }

    private void assertResponse(
            ApiErrorDTO body,
            HttpStatus status,
            String error
    ) {
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(status.value());
        assertThat(body.error()).isEqualTo(error);
        assertThat(body.path()).isEqualTo("/app/v1/workflows");
        assertThat(body.timestamp()).isNotNull();
    }

    public static class RequestModel {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
