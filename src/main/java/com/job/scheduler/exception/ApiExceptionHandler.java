package com.job.scheduler.exception;

import com.job.scheduler.dto.ApiErrorDTO;
import com.job.scheduler.dto.ApiFieldErrorDTO;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import com.job.scheduler.workflow.asl.validation.AslDefinitionValidationException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AslDefinitionValidationException.class)
    public ResponseEntity<ApiErrorDTO> handleAslDefinitionValidation(
            AslDefinitionValidationException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldErrorDTO> fieldErrors = exception.getIssues()
                .stream()
                .map(issue -> new ApiFieldErrorDTO(
                        issue.location(),
                        issue.code() + ": " + issue.message()
                ))
                .toList();

        return build(
                HttpStatus.BAD_REQUEST,
                "ASL_VALIDATION_ERROR",
                exception.getMessage(),
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorDTO> handleRequestValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldErrorDTO> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiFieldErrorDTO(error.getField(), error.getDefaultMessage()))
                .toList();

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorDTO> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldErrorDTO> fieldErrors = exception.getConstraintViolations()
                .stream()
                .map(violation -> new ApiFieldErrorDTO(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                ))
                .toList();

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiErrorDTO> handleBadRequest(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorDTO> handleNotFound(EntityNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorDTO> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorDTO> handleConflict(IllegalStateException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "INVALID_STATE", exception.getMessage(), request);
    }

    @ExceptionHandler(McpConnectionException.class)
    public ResponseEntity<ApiErrorDTO> handleMcpConnection(
            McpConnectionException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_GATEWAY, "MCP_CONNECTION_ERROR", exception.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorDTO> handleOptimisticLock(
            OptimisticLockingFailureException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "Workflow metadata was modified by another request",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorDTO> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred",
                request
        );
    }

    private ResponseEntity<ApiErrorDTO> build(
            HttpStatus status,
            String error,
            String message,
            HttpServletRequest request
    ) {
        return build(status, error, message, request, List.of());
    }

    private ResponseEntity<ApiErrorDTO> build(
            HttpStatus status,
            String error,
            String message,
            HttpServletRequest request,
            List<ApiFieldErrorDTO> fieldErrors
    ) {
        ApiErrorDTO response = new ApiErrorDTO(
                Instant.now(),
                status.value(),
                error,
                message,
                request.getRequestURI(),
                fieldErrors
        );

        return ResponseEntity.status(status).body(response);
    }
}
