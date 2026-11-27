package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.integration.claude.service.ClaudeTemplateService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeTemplateServiceTest {

    private final ClaudeTemplateService service = new ClaudeTemplateService();

    @Test
    void templateWorkbookHasTheReferenceHeadersInOrder() throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(service.workbook()))) {
            assertThat(wb.getSheetName(0)).isEqualTo("Sheet1");
            Row header = wb.getSheetAt(0).getRow(0);
            List<String> headers = new ArrayList<>();
            header.forEach(c -> headers.add(c.getStringCellValue()));
            assertThat(headers).containsExactlyElementsOf(
                    ClaudeTestFixtures.HEADERS.stream().map(String::trim).toList());
            assertThat(wb.getSheet("Image names")).isNotNull();
            assertThat(wb.getSheet("Column guide")).isNotNull();
        }
    }

    @Test
    void jsonDescriptionCoversEveryColumn() {
        Map<String, Object> d = service.describe();
        assertThat((List<?>) d.get("columns")).hasSize(32);
        assertThat(d).containsKey("images");
    }
}
