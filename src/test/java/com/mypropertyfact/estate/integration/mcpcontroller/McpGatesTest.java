package com.mypropertyfact.estate.integration.mcpcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.integration.mcpcontroller.service.McpGates;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpGatesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception { return mapper.readTree(s); }

    @Test
    void duplicateStatusTextIsMatch() throws Exception {
        assertThat(McpGates.duplicates(json("{\"issues\":[{\"code\":\"DUPLICATE_REVIEW_REQUIRED\"}],\"duplicates\":[]}")))
                .isEqualTo(McpGates.DuplicateCheck.MATCH);
    }

    @Test
    void explicitZeroIsClearAndSilenceIsUnknown() throws Exception {
        assertThat(McpGates.duplicates(json("{\"duplicates\":[]}"))).isEqualTo(McpGates.DuplicateCheck.CLEAR);
        assertThat(McpGates.duplicates(json("{\"status\":\"VALID\"}"))).isEqualTo(McpGates.DuplicateCheck.UNKNOWN);
        assertThat(McpGates.duplicates(json("{\"error\":\"INVALID_TOOL_INPUT\"}"))).isEqualTo(McpGates.DuplicateCheck.UNKNOWN);
    }

    @Test
    void backendReportShapes() throws Exception {
        assertThat(McpGates.duplicates(json("{\"valid\":true,\"duplicateRows\":0,\"rows\":[{\"status\":\"VALID\",\"existingProjectId\":null,\"duplicateReasons\":[]}]}")))
                .isEqualTo(McpGates.DuplicateCheck.CLEAR);
        // duplicateRows=0 must not hide a row-level duplicate signal
        assertThat(McpGates.duplicates(json("{\"duplicateRows\":0,\"rows\":[{\"existingProjectId\":1394}]}")))
                .isEqualTo(McpGates.DuplicateCheck.MATCH);
        assertThat(McpGates.duplicates(json("{\"duplicateRows\":0,\"rows\":[{\"duplicateReasons\":[\"RERA\"]}]}")))
                .isEqualTo(McpGates.DuplicateCheck.MATCH);
        assertThat(McpGates.duplicates(json("{\"duplicateRows\":1}"))).isEqualTo(McpGates.DuplicateCheck.MATCH);
    }

    @Test
    void acceptedNeedsExplicitValidEverywhere() throws Exception {
        assertThat(McpGates.accepted(json("{\"httpStatus\":200,\"valid\":true}"))).isTrue();
        assertThat(McpGates.accepted(json("{\"httpStatus\":200,\"body\":{\"valid\":true}}"))).isTrue();
        assertThat(McpGates.accepted(json("{\"httpStatus\":200,\"body\":{}}"))).isFalse();
        assertThat(McpGates.accepted(json("{\"httpStatus\":422,\"valid\":true}"))).isFalse();
        assertThat(McpGates.accepted(json("{\"httpStatus\":200,\"valid\":true,\"body\":{\"valid\":false}}"))).isFalse();
        assertThat(McpGates.accepted(json("{\"httpStatus\":200,\"valid\":true,\"imageValidation\":{\"valid\":false},\"body\":{\"valid\":true}}"))).isFalse();
        assertThat(McpGates.accepted(null)).isFalse();
    }

    @Test
    void summaryHasCodesButNoContent() throws Exception {
        JsonNode resp = json("{\"httpStatus\":200,\"imageValidation\":{\"valid\":false,\"summary\":{\"images\":3,\"errors\":1}},"
                + "\"body\":{\"valid\":false,\"totalRows\":1,\"errorCount\":1,"
                + "\"workbookIssues\":[{\"severity\":\"WARNING\",\"code\":\"UNKNOWN_HEADER\",\"message\":\"secret text\"}],"
                + "\"rows\":[{\"status\":\"BLOCKED\",\"projectName\":\"Private Project\",\"errors\":[{\"severity\":\"ERROR\",\"code\":\"UNKNOWN_CITY\"}]}]}}");
        JsonNode s = McpGates.summary(resp, mapper);
        assertThat(s.path("errorCodes").get(0).asText()).isEqualTo("UNKNOWN_CITY");
        assertThat(s.path("warningCodes").get(0).asText()).isEqualTo("UNKNOWN_HEADER");
        assertThat(s.path("images").path("errors").asInt()).isEqualTo(1);
        assertThat(s.toString()).doesNotContain("Private Project").doesNotContain("secret text");
    }
}
