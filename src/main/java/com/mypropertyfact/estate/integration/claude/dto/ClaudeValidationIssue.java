package com.mypropertyfact.estate.integration.claude.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One machine-readable finding. {@code severity} ERROR blocks; WARNING and INFO do not. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClaudeValidationIssue {

    public enum Severity {ERROR, WARNING, INFO}

    private Severity severity;
    private String code;
    private String message;
    private Integer excelRow;
    private String column;
    private String value;

    public static ClaudeValidationIssue error(String code, String message) {
        return new ClaudeValidationIssue(Severity.ERROR, code, message, null, null, null);
    }

    public static ClaudeValidationIssue warning(String code, String message) {
        return new ClaudeValidationIssue(Severity.WARNING, code, message, null, null, null);
    }

    public static ClaudeValidationIssue info(String code, String message) {
        return new ClaudeValidationIssue(Severity.INFO, code, message, null, null, null);
    }

    public ClaudeValidationIssue row(int excelRow) {
        this.excelRow = excelRow;
        return this;
    }

    public ClaudeValidationIssue column(String column) {
        this.column = column;
        return this;
    }

    public ClaudeValidationIssue value(String value) {
        this.value = ClaudeText.truncate(value, 200);
        return this;
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
