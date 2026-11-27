package com.mypropertyfact.estate.integration.claude.exception;

/** Thrown inside a row transaction when the row's identity is already taken; rolls the row back. */
public class ClaudeDuplicateException extends RuntimeException {

    private final Integer existingProjectId;

    public ClaudeDuplicateException(String message, Integer existingProjectId) {
        super(message);
        this.existingProjectId = existingProjectId;
    }

    public Integer getExistingProjectId() {
        return existingProjectId;
    }
}
