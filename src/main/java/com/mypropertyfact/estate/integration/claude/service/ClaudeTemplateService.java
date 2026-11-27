package com.mypropertyfact.estate.integration.claude.service;

import com.mypropertyfact.estate.integration.claude.spec.ClaudeColumn;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/** The supported template, derived from the reference workbook's 32 headers. */
@Service
public class ClaudeTemplateService {

    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheetName", "Sheet1");
        out.put("headerRow", 1);
        out.put("firstDataRow", 2);
        out.put("headerMatching", "Case- and whitespace-insensitive (\" PROJECT CONFIGURATION\" and \"PROJECT Amenities\" are accepted). Column order does not matter.");
        List<Map<String, Object>> cols = new ArrayList<>();
        for (int i = 0; i < ClaudeImporterSpec.REFERENCE_WORKBOOK_HEADERS.size(); i++) {
            String raw = ClaudeImporterSpec.REFERENCE_WORKBOOK_HEADERS.get(i);
            String norm = ClaudeText.normalizeHeader(raw);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("column", ClaudeText.columnLetter(i));
            c.put("header", raw.trim());
            Optional<ClaudeColumn> col = ClaudeImporterSpec.column(norm);
            if (col.isPresent()) {
                c.put("required", col.get().required());
                c.put("maxLength", col.get().maxLength() == 0 ? null : col.get().maxLength());
                c.put("target", col.get().target());
            } else {
                c.put("required", false);
                c.put("target", "project_faqs (Question n / Answer n must both be filled; pairs 1–20 supported)");
            }
            cols.add(c);
        }
        out.put("columns", cols);
        out.put("optionalExtraColumns", List.of(Map.of("header", ClaudeColumn.PROJECT_ABOUT_SHORT_DESCRIPTION.header(),
                "target", ClaudeColumn.PROJECT_ABOUT_SHORT_DESCRIPTION.target())));
        out.put("images", imageRules());
        return out;
    }

    public Map<String, Object> imageRules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileName", "<Project-Name-With-Hyphens>_<Type>_My-Property-Fact.<jpg|jpeg|png|webp>");
        m.put("projectKey", "PROJECT NAME with spaces replaced by '-' (it is matched by URL slug). It is NOT a database id.");
        m.put("folders", "Optional. If used, the folder name must be the same project key. File names must be unique across the whole ZIP.");
        List<Map<String, Object>> types = new ArrayList<>();
        for (ImageKind k : ImageKind.values()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("slot", k.name());
            t.put("types", switch (k) {
                case LOGO -> "Logo";
                case THUMBNAIL -> "Thumbnail";
                case LOCATION -> "Location or LocationMap";
                case DESKTOP -> "Desktop-1, Desktop-2 … (or DesktopBanner)";
                case MOBILE -> "Mobile-1, Mobile-2 … (or MobileBanner)";
                case GALLERY -> "Gallery-1, Gallery-2 …";
            });
            t.put("storedSize", "fit within " + k.boxWidth + "x" + k.boxHeight + " (aspect kept, re-encoded)");
            t.put("maxPerProject", k.singlePerProject() ? 1 : null);
            types.add(t);
        }
        m.put("types", types);
        m.put("example", "The-Core-Ultra-Wide_Desktop-1_My-Property-Fact.jpg");
        return m;
    }

    public byte[] workbook() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Row header = sheet.createRow(0);
            List<String> headers = ClaudeImporterSpec.REFERENCE_WORKBOOK_HEADERS;
            for (int i = 0; i < headers.size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headers.get(i).trim());
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, 22 * 256);
            }

            Sheet guide = wb.createSheet("Column guide");
            Row gh = guide.createRow(0);
            String[] gcols = {"Column", "Header", "Required", "Max length", "Stored in"};
            for (int i = 0; i < gcols.length; i++) {
                Cell c = gh.createCell(i);
                c.setCellValue(gcols[i]);
                c.setCellStyle(bold);
            }
            int r = 1;
            for (Object o : (List<?>) describe().get("columns")) {
                Map<?, ?> col = (Map<?, ?>) o;
                Row row = guide.createRow(r++);
                row.createCell(0).setCellValue(String.valueOf(col.get("column")));
                row.createCell(1).setCellValue(String.valueOf(col.get("header")));
                row.createCell(2).setCellValue(Boolean.TRUE.equals(col.get("required")) ? "Yes" : "No");
                row.createCell(3).setCellValue(col.get("maxLength") == null ? "" : String.valueOf(col.get("maxLength")));
                row.createCell(4).setCellValue(String.valueOf(col.get("target")));
            }
            guide.setColumnWidth(1, 36 * 256);
            guide.setColumnWidth(4, 120 * 256);

            Sheet img = wb.createSheet("Image names");
            Row ih = img.createRow(0);
            String[] icols = {"Slot", "Type token(s)", "Stored size", "Max per project"};
            for (int i = 0; i < icols.length; i++) {
                Cell c = ih.createCell(i);
                c.setCellValue(icols[i]);
                c.setCellStyle(bold);
            }
            r = 1;
            Map<String, Object> rules = imageRules();
            for (Object o : (List<?>) rules.get("types")) {
                Map<?, ?> t = (Map<?, ?>) o;
                Row row = img.createRow(r++);
                row.createCell(0).setCellValue(String.valueOf(t.get("slot")));
                row.createCell(1).setCellValue(String.valueOf(t.get("types")));
                row.createCell(2).setCellValue(String.valueOf(t.get("storedSize")));
                row.createCell(3).setCellValue(t.get("maxPerProject") == null ? "any" : String.valueOf(t.get("maxPerProject")));
            }
            r++;
            img.createRow(r++).createCell(0).setCellValue("File name: " + rules.get("fileName"));
            img.createRow(r++).createCell(0).setCellValue("Project key: " + rules.get("projectKey"));
            img.createRow(r++).createCell(0).setCellValue("Folders: " + rules.get("folders"));
            img.createRow(r).createCell(0).setCellValue("Example: " + rules.get("example"));
            img.setColumnWidth(1, 44 * 256);
            img.setColumnWidth(2, 40 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Template could not be generated", e);
        }
    }
}
