package com.agentflow.shared.web;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.BadSqlGrammarException;

/**
 * Centralised exception translation. Module-specific exceptions extend
 * {@link AgentFlowException}; unknown exceptions never leak internals to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentFlowException.class)
    ResponseEntity<ApiError> handleDomain(AgentFlowException ex) {
        return build(ex.status(), ex.code(), ex.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldViolation(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", violations);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return build(status, status.name(), ex.getReason() == null ? status.getReasonPhrase() : ex.getReason(), List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        if (ex instanceof BadSqlGrammarException sqlFailure) {
            var sqlException = sqlFailure.getSQLException();
            // Prepared SQL contains placeholders only; never log bound values or exception messages.
            log.error("Database query failure sqlState={} vendorCode={} sql={}",
                    sqlException.getSQLState(), sqlException.getErrorCode(), sqlFailure.getSql());
        }
        log.error("Unhandled request failure type={} correlationId={}", ex.getClass().getSimpleName(),
                MDC.get(CorrelationIdFilter.MDC_KEY));
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           List<ApiError.FieldViolation> violations) {
        ApiError body = new ApiError(Instant.now(), status.value(), code, message,
                MDC.get(CorrelationIdFilter.MDC_KEY), violations);
        return ResponseEntity.status(status).body(body);
    }
}
