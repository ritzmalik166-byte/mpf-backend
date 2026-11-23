package com.mypropertyfact.estate.integration.claude.exception;

import org.springframework.http.HttpStatus;

/** API-level error with a stable machine-readable code. Messages never contain paths or stack traces. */
public class ClaudeIntegrationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object body;

    public ClaudeIntegrationException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ClaudeIntegrationException(HttpStatus status, String code, String message, Object body) {
        super(message);
        this.status = status;
        this.code = code;
        this.body = body;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Object getBody() {
        return body;
    }
}
