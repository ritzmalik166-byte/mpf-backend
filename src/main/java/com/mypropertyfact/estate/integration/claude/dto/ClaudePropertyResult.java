package com.mypropertyfact.estate.integration.claude.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClaudePropertyResult {
    private int excelRow;
    private String sourceReference;
    private String projectName;
    private String builderName;
    private String cityName;
    private String reraNo;
    private Integer projectId;
    private Integer existingProjectId;
    private String slug;
    /** Read back from the database: true when projects.status = false. */
    private Boolean draft;
    private boolean verified;
    private Integer builderId;
    private boolean builderCreated;
    private int imagesExpected;
    private int imagesImported;
    private String importStatus;
    private List<ClaudeValidationIssue> errors = new ArrayList<>();
    private List<ClaudeValidationIssue> warnings = new ArrayList<>();
    private Map<String, String> unappliedFields = new LinkedHashMap<>();
}
