package com.agentflow.shared.web;

import org.springframework.http.HttpStatus;

/** Base type for all expected, client-facing domain failures. */
public class AgentFlowException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AgentFlowException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
