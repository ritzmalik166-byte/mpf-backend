package com.mypropertyfact.estate.integration.claude.entity;

public enum ClaudeItemStatus {
    /** Passed validation, waiting for the job thread. */
    PENDING,
    /** New unpublished draft created (images may still have partial errors, see imagesImported). */
    CREATED_DRAFT,
    /** Matches an existing project (slug, RERA or name+builder+city) or a concurrent claim. Nothing written. */
    DUPLICATE_REVIEW_REQUIRED,
    /** Row had blocking validation errors and was not imported. */
    SKIPPED_VALIDATION,
    /** Import of this row failed; its transaction was rolled back. */
    FAILED
}
