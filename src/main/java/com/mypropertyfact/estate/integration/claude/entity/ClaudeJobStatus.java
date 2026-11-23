package com.mypropertyfact.estate.integration.claude.entity;

public enum ClaudeJobStatus {
    RECEIVED,
    VALIDATING,
    VALIDATION_FAILED,
    QUEUED,
    PROCESSING,
    PARTIALLY_COMPLETED,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == VALIDATION_FAILED || this == PARTIALLY_COMPLETED || this == COMPLETED || this == FAILED;
    }
}
