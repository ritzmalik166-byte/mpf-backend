package com.mypropertyfact.estate.integration.claude.model;

import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workbook as read by {@link com.mypropertyfact.estate.integration.claude.processor.ClaudeWorkbookParser}. */
public class ClaudeParsedWorkbook {

    public record Header(int index, String letter, String raw, String normalized) {
    }

    /** One data row: normalized header → trimmed cell text (blank cells are absent). */
    public record Row(int excelRow, Map<String, String> values) {
        public String get(String normalizedHeader) {
            return values.get(normalizedHeader);
        }
    }

    public String format;
    public String sheetName;
    public List<String> sheetNames = new ArrayList<>();
    public List<Header> headers = new ArrayList<>();
    /** normalized header → header (first occurrence). Duplicates are reported as issues. */
    public Map<String, Header> headerIndex = new LinkedHashMap<>();
    public List<Row> rows = new ArrayList<>();
    public List<ClaudeValidationIssue> issues = new ArrayList<>();
    /** True when the file could not be opened at all. */
    public boolean unreadable;
}
