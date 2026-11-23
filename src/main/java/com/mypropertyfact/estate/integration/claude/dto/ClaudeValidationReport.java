package com.mypropertyfact.estate.integration.claude.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Full dry-run report. Produced by /properties/validate and stored with every job. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClaudeValidationReport {

    /** True when there are no ERROR issues anywhere (workbook, ZIP or any row). */
    private boolean valid;
    /** Rows that would be imported if submitted with allowPartial=true. */
    private int importableRows;
    private int blockedRows;
    private int duplicateRows;
    private int totalRows;
    private int errorCount;
    private int warningCount;

    private WorkbookInfo workbook = new WorkbookInfo();
    private ZipInfo zip;
    private List<ClaudeValidationIssue> workbookIssues = new ArrayList<>();
    private List<RowReport> rows = new ArrayList<>();

    @Data
    public static class WorkbookInfo {
        private String filename;
        private String format;
        private String sheetName;
        private List<String> sheetNames = new ArrayList<>();
        private int headerRow = 1;
        private int firstDataRow = 2;
        private int lastDataRow;
        private int dataRows;
        private List<HeaderInfo> detectedHeaders = new ArrayList<>();
        private List<String> missingRequiredHeaders = new ArrayList<>();
        private List<String> unknownHeaders = new ArrayList<>();
        /** Populated columns whose values are not (fully) stored on the project, with the reason. */
        private Map<String, String> unsupportedFields = new LinkedHashMap<>();
    }

    @Data
    public static class HeaderInfo {
        private String column;
        private String rawHeader;
        private String normalizedHeader;
        private String mappedTo;
        /** MAPPED, REFERENCE_ONLY, NEW_BUILDER_ONLY, FAQ, UNKNOWN, BLANK, DUPLICATE */
        private String status;
    }

    @Data
    public static class ZipInfo {
        private String filename;
        private int entries;
        private int images;
        private long extractedBytes;
        private List<ClaudeValidationIssue> issues = new ArrayList<>();
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RowReport {
        private int excelRow;
        private String sourceReference;
        private String projectName;
        private String builderName;
        private String cityName;
        private String reraNo;
        private String predictedSlug;
        /** VALID, BLOCKED, DUPLICATE_REVIEW_REQUIRED */
        private String status;
        private Integer existingProjectId;
        private List<String> duplicateReasons = new ArrayList<>();
        private boolean newBuilder;
        private Integer builderId;
        private List<String> amenitiesMatched = new ArrayList<>();
        private int floorPlans;
        private int locationBenefits;
        private int faqs;
        private List<ImageInfo> images = new ArrayList<>();
        private List<ClaudeValidationIssue> errors = new ArrayList<>();
        private List<ClaudeValidationIssue> warnings = new ArrayList<>();
        private Map<String, String> unappliedFields = new LinkedHashMap<>();
    }

    @Data
    public static class ImageInfo {
        private String file;
        private String type;
        private String slot;
        private int width;
        private int height;
        private String format;
    }
}
