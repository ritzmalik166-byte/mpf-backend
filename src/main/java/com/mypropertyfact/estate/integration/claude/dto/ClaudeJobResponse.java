package com.mypropertyfact.estate.integration.claude.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClaudeJobResponse {
    private String jobId;
    private String batchName;
    private String status;
    private boolean allowPartial;
    private int totalProperties;
    private int successfulProperties;
    private int failedProperties;
    private int duplicateProperties;
    private int skippedProperties;
    private int pendingProperties;
    private String originalExcelFilename;
    private String originalZipFilename;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorSummary;
    private String statusUrl;
    private String resultsUrl;
}
