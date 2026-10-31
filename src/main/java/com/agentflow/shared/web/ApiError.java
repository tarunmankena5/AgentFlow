package com.agentflow.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Uniform error envelope returned by every endpoint. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String correlationId,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
