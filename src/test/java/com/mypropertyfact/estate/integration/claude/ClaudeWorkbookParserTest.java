package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.integration.claude.model.ClaudeParsedWorkbook;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeWorkbookParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeWorkbookParserTest {

    private final ClaudeWorkbookParser parser = new ClaudeWorkbookParser();

    @Test
    void readsTheReferenceWorkbookExactly() throws Exception {
        ClaudeParsedWorkbook wb = parser.parse(ClaudeTestFixtures.referenceWorkbook(), 500);

        assertThat(wb.unreadable).isFalse();
        assertThat(wb.format).isEqualTo("xlsx");
        assertThat(wb.sheetName).isEqualTo("Sheet1");
        assertThat(wb.sheetNames).containsExactly("Sheet1");
        assertThat(wb.headers).hasSize(32);
        assertThat(wb.headers.get(0).letter()).isEqualTo("A");
        assertThat(wb.headers.get(31).letter()).isEqualTo("AF");
        assertThat(wb.headers.stream().map(ClaudeParsedWorkbook.Header::raw).toList())
                .containsExactlyElementsOf(ClaudeTestFixtures.HEADERS);
        // Leading space in F and mixed case in V are normalised.
        assertThat(wb.headers.get(5).normalized()).isEqualTo("PROJECT CONFIGURATION");
        assertThat(wb.headers.get(21).normalized()).isEqualTo("PROJECT AMENITIES");
        // Column AG is blank and is not treated as a field; no errors at all.
        assertThat(wb.issues).isEmpty();

        assertThat(wb.rows).hasSize(20);
        assertThat(wb.rows.get(0).excelRow()).isEqualTo(2);
        assertThat(wb.rows.get(19).excelRow()).isEqualTo(21);

        ClaudeParsedWorkbook.Row first = wb.rows.get(0);
        assertThat(first.get("ID")).isEqualTo("1");
        assertThat(first.get("PROJECT NAME")).isEqualTo("The Core Ultra Wide");
        assertThat(first.get("PROJECT CONFIGURATION")).isEqualTo("Studo Apartment-525 sq.ft");
        assertThat(first.get("IVR NO")).isEqualTo("8920024793");
        assertThat(first.get("PROJECT PRICE")).isEqualTo("0.75");
        assertThat(first.get("QUESTION 4")).isEqualTo("What is the RERA number of The Core Ultra Wide?");
        assertThat(first.get("ANSWER 4")).startsWith("The RERA registration number");

        assertThat(wb.rows.get(3).get("PROJECT PRICE")).isEqualTo("Price on Request");
        assertThat(wb.rows.get(3).get("PROJECT STATUS NAME")).isEqualTo("New Launch");
    }

    @Test
    void rejectsNonWorkbookContentEvenWithXlsxName() {
        ClaudeParsedWorkbook wb = parser.parse("not a workbook at all".getBytes(StandardCharsets.UTF_8), 500);
        assertThat(wb.unreadable).isTrue();
        assertThat(wb.issues).anyMatch(i -> "EXCEL_NOT_A_WORKBOOK".equals(i.getCode()));
    }

    @Test
    void reportsValuesWithoutHeaderAndDuplicateHeaders() throws Exception {
        byte[] bytes = ClaudeTestFixtures.workbook(List.of("PROJECT NAME", "project name", ""),
                List.of(Map.of("PROJECT NAME", "X", "", "orphan value")));
        ClaudeParsedWorkbook wb = parser.parse(bytes, 500);
        assertThat(wb.issues).anyMatch(i -> "EXCEL_DUPLICATE_HEADER".equals(i.getCode()));
        assertThat(wb.issues).anyMatch(i -> "EXCEL_VALUE_WITHOUT_HEADER".equals(i.getCode()) && "C".equals(i.getColumn()));
    }

    @Test
    void readsFirstSheetAndWarnsAboutOthers() throws Exception {
        byte[] bytes = ClaudeTestFixtures.workbook("Projects", List.of("PROJECT NAME"), List.of(Map.of("PROJECT NAME", "X")));
        ClaudeParsedWorkbook wb = parser.parse(bytes, 500);
        assertThat(wb.sheetName).isEqualTo("Projects");
        assertThat(wb.issues).anyMatch(i -> "EXCEL_SHEET_NAME".equals(i.getCode()) && !i.isError());
    }

    @Test
    void enforcesRowLimit() throws Exception {
        byte[] bytes = ClaudeTestFixtures.workbook(List.of("PROJECT NAME"),
                List.of(Map.of("PROJECT NAME", "A"), Map.of("PROJECT NAME", "B"), Map.of("PROJECT NAME", "C")));
        ClaudeParsedWorkbook wb = parser.parse(bytes, 2);
        assertThat(wb.issues).anyMatch(i -> "EXCEL_TOO_MANY_ROWS".equals(i.getCode()));
    }
}
