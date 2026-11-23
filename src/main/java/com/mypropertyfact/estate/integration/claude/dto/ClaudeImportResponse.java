package com.mypropertyfact.estate.integration.claude.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** Answer to POST /properties/import. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClaudeImportResponse {
    private String jobId;
    private String status;
    private boolean dryRun;
    private String message;
    private String statusUrl;
    private String resultsUrl;
    private ClaudeValidationReport validation;
}
