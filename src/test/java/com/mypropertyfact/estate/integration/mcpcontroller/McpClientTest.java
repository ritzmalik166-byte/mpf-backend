package com.mypropertyfact.estate.integration.mcpcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.integration.mcpcontroller.client.McpClient;
import com.mypropertyfact.estate.integration.mcpcontroller.service.McpControllerService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

class McpClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsHttpsAndLocalHttp() {
        assertThatCode(() -> new McpClient("https://mpf.example.com/api/property-automation/mcp/" + KEY, mapper)).doesNotThrowAnyException();
        assertThatCode(() -> new McpClient("http://localhost:3000/api/property-automation/mcp/" + KEY, mapper)).doesNotThrowAnyException();
        assertThatCode(() -> new McpClient("http://127.0.0.1:3000/api/property-automation/mcp/" + KEY, mapper)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeUrlsWithoutEchoingTheKey() {
        String[] bad = {
                null, "", "not a url",
                "http://mpf.example.com/api/property-automation/mcp/" + KEY,
                "https://mpf.example.com/api/property-automation/mcp/",
                "https://mpf.example.com/api/other/" + KEY,
                "https://user:pw@mpf.example.com/api/property-automation/mcp/" + KEY,
                "https://mpf.example.com/api/property-automation/mcp/" + KEY + "#x",
                "https://mpf.example.com/api/property-automation/mcp/" + KEY + "?a=b",
                "ftp://mpf.example.com/api/property-automation/mcp/" + KEY,
        };
        for (String url : bad) {
            Throwable t = catchThrowable(() -> new McpClient(url, mapper));
            assertThat(t).as(String.valueOf(url)).isInstanceOf(IllegalArgumentException.class);
            assertThat(t.getMessage()).doesNotContain(KEY);
        }
    }

    @Test
    void writeToolsAreNeverAllowed() {
        assertThat(McpControllerService.ALLOWED_CALLS)
                .doesNotContain("import_properties", "create_upload_session", "get_import_job", "get_import_results")
                .containsAll(McpControllerService.REQUIRED_TOOLS);
    }
}
