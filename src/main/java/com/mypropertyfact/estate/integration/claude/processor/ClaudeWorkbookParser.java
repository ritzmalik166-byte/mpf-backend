package com.mypropertyfact.estate.integration.claude.processor;

import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;
import com.mypropertyfact.estate.integration.claude.model.ClaudeParsedWorkbook;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * Reads the workbook the same way the existing importer does (first sheet, header row 1, header
 * names normalised, numeric cells without trailing ".0"), but keeps everything it finds so the
 * validator can report blank-header columns, duplicates and unknown headers instead of dropping them.
 */
@Component
public class ClaudeWorkbookParser {

    public ClaudeParsedWorkbook parse(byte[] bytes, int maxRows) {
        ClaudeParsedWorkbook wb = new ClaudeParsedWorkbook();
        wb.format = detectFormat(bytes);
        if (wb.format == null) {
            wb.unreadable = true;
            wb.issues.add(ClaudeValidationIssue.error("EXCEL_NOT_A_WORKBOOK",
                    "The file is not an Excel workbook (.xlsx or .xls). The content was checked, not just the name."));
            return wb;
        }
        try (InputStream is = new ByteArrayInputStream(bytes);
             Workbook workbook = WorkbookFactory.create(is)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                wb.sheetNames.add(workbook.getSheetName(i));
            }
            if (workbook.getNumberOfSheets() == 0) {
                wb.unreadable = true;
                wb.issues.add(ClaudeValidationIssue.error("EXCEL_NO_SHEETS", "The workbook has no sheets."));
                return wb;
            }
            // Same as the existing importer: the first sheet is the data sheet.
            Sheet sheet = workbook.getSheetAt(0);
            wb.sheetName = sheet.getSheetName();
            if (workbook.getNumberOfSheets() > 1) {
                wb.issues.add(ClaudeValidationIssue.warning("EXCEL_EXTRA_SHEETS",
                        "Only the first sheet (\"" + wb.sheetName + "\") is read; other sheets are ignored: "
                                + wb.sheetNames.subList(1, wb.sheetNames.size())));
            }
            if (!"Sheet1".equals(wb.sheetName)) {
                wb.issues.add(ClaudeValidationIssue.info("EXCEL_SHEET_NAME",
                        "First sheet is named \"" + wb.sheetName + "\" (reference workbook uses \"Sheet1\"). It is read anyway."));
            }
            readHeaders(sheet, wb);
            if (wb.headers.isEmpty()) {
                wb.issues.add(ClaudeValidationIssue.error("EXCEL_NO_HEADER_ROW", "Row 1 must contain the column headers."));
                return wb;
            }
            readRows(sheet, wb, maxRows);
        } catch (Exception e) {
            wb.unreadable = true;
            wb.issues.add(ClaudeValidationIssue.error("EXCEL_UNREADABLE",
                    "The workbook could not be opened: " + ClaudeText.truncate(e.getClass().getSimpleName() + " " + e.getMessage(), 200)));
        }
        return wb;
    }

    private void readHeaders(Sheet sheet, ClaudeParsedWorkbook wb) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            return;
        }
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            String raw = cellText(headerRow.getCell(i));
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String normalized = ClaudeText.normalizeHeader(raw);
            ClaudeParsedWorkbook.Header h = new ClaudeParsedWorkbook.Header(i, ClaudeText.columnLetter(i), raw, normalized);
            wb.headers.add(h);
            if (wb.headerIndex.containsKey(normalized)) {
                wb.issues.add(ClaudeValidationIssue.error("EXCEL_DUPLICATE_HEADER",
                                "Header \"" + raw.trim() + "\" appears more than once (columns "
                                        + wb.headerIndex.get(normalized).letter() + " and " + h.letter() + ").")
                        .column(h.letter()));
            } else {
                wb.headerIndex.put(normalized, h);
            }
        }
    }

    private void readRows(Sheet sheet, ClaudeParsedWorkbook wb, int maxRows) {
        Set<Integer> headerCols = new HashSet<>();
        wb.headers.forEach(h -> headerCols.add(h.index()));
        Map<Integer, Integer> unheadedColumns = new TreeMap<>();
        int dataRows = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            boolean any = false;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String text = cellText(row.getCell(c));
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                any = true;
                if (!headerCols.contains(c)) {
                    unheadedColumns.merge(c, r + 1, Math::min);
                }
            }
            if (!any) {
                continue;
            }
            for (ClaudeParsedWorkbook.Header h : wb.headerIndex.values()) {
                String text = cellText(row.getCell(h.index()));
                if (text != null && !text.trim().isEmpty()) {
                    values.put(h.normalized(), text.trim());
                }
            }
            dataRows++;
            if (dataRows > maxRows) {
                wb.issues.add(ClaudeValidationIssue.error("EXCEL_TOO_MANY_ROWS",
                        "The workbook has more than " + maxRows + " data rows. Split it into smaller batches."));
                break;
            }
            wb.rows.add(new ClaudeParsedWorkbook.Row(r + 1, values));
        }
        unheadedColumns.forEach((col, firstRow) -> wb.issues.add(ClaudeValidationIssue.error("EXCEL_VALUE_WITHOUT_HEADER",
                        "Column " + ClaudeText.columnLetter(col) + " has values (first at row " + firstRow
                                + ") but no header, so it cannot be mapped.")
                .column(ClaudeText.columnLetter(col))));
        if (wb.rows.isEmpty()) {
            wb.issues.add(ClaudeValidationIssue.error("EXCEL_NO_DATA_ROWS", "The workbook has a header row but no data rows."));
        }
    }

    /**
     * Same conversion as the existing importer's {@code getCellValueAsString}: whole numbers lose
     * the ".0" ({@code 8920024793}), decimals keep it ({@code 0.75}). Formula cells use their cached
     * result type instead of guessing.
     */
    static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double n = cell.getNumericCellValue();
                if (n == Math.floor(n) && !Double.isInfinite(n) && Math.abs(n) < 1e15) {
                    return String.valueOf((long) n);
                }
                return String.valueOf(n);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return null;
        }
    }

    /** Content sniffing: OOXML (zip "PK") or legacy OLE2 (D0 CF 11 E0). */
    static String detectFormat(byte[] b) {
        if (b == null || b.length < 8) {
            return null;
        }
        if (b[0] == 0x50 && b[1] == 0x4B && b[2] == 0x03 && b[3] == 0x04) {
            return "xlsx";
        }
        if ((b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0) {
            return "xls";
        }
        return null;
    }
}
