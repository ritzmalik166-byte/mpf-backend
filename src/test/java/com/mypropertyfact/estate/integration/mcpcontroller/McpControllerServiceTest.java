package com.mypropertyfact.estate.integration.mcpcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mypropertyfact.estate.integration.mcpcontroller.config.McpControllerProperties;
import com.mypropertyfact.estate.integration.mcpcontroller.service.McpControllerService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the service against a fake MCP gateway on localhost and records every tool it calls. */
class McpControllerServiceTest {
    private static final String KEY = "test-key-0123456789abcdefghij";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> toolCalls = new CopyOnWriteArrayList<>();
    /** What the fake gateway returns for validate_properties. */
    private volatile String validateBody;
    private HttpServer server;
    private McpControllerService service;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/property-automation/mcp/" + KEY, exchange -> {
            JsonNode rpc = mapper.readTree(exchange.getRequestBody());
            String method = rpc.path("method").asText();
            ObjectNode result = mapper.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-06-18");
                    result.putObject("serverInfo").put("name", "fake-gateway").put("version", "test");
                }
                case "tools/list" -> {
                    var tools = result.putArray("tools");
                    for (String t : List.of("get_template", "validate_properties", "get_property_details",
                            "import_properties", "create_upload_session")) tools.addObject().put("name", t);
                }
                case "tools/call" -> {
                    String tool = rpc.path("params").path("name").asText();
                    toolCalls.add(tool);
                    String structured = tool.equals("validate_properties") ? validateBody : "{\"httpStatus\":200,\"body\":{}}";
                    JsonNode s = mapper.readTree(structured);
                    result.set("structuredContent", s);
                    result.put("isError", s.path("httpStatus").asInt(200) >= 400);
                }
                default -> { }
            }
            ObjectNode resp = mapper.createObjectNode().put("jsonrpc", "2.0");
            resp.set("id", rpc.get("id"));
            resp.set("result", result);
            byte[] bytes = mapper.writeValueAsBytes(resp);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();

        McpControllerProperties props = new McpControllerProperties();
        props.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/property-automation/mcp/" + KEY);
        service = new McpControllerService(props, mapper);
    }

    @AfterEach
    void stop() { server.stop(0); }

    private JsonNode manifest(String extra) throws Exception {
        return mapper.readTree("{\"properties\":[{\"PROJECT NAME\":\"X\",\"BUILDER NAME\":\"B\"}]" + extra + "}");
    }

    @Test
    void probePassesAndOnlyReads() {
        ObjectNode report = service.probe(1394);
        assertThat(report.path("status").asText()).isEqualTo("PASS");
        assertThat(report.path("writePerformed").asBoolean()).isFalse();
        assertThat(report.path("propertyIdChecked").asInt()).isEqualTo(1394);
        assertThat(toolCalls).containsExactly("get_template", "get_property_details");
        assertThat(report.toString()).doesNotContain(KEY);
        assertThat(service.status().path("lastRun").path("status").asText()).isEqualTo("PASS");
    }

    @Test
    void duplicateIsBlocked() throws Exception {
        validateBody = "{\"httpStatus\":200,\"body\":{\"valid\":false,\"duplicateRows\":1,"
                + "\"rows\":[{\"status\":\"DUPLICATE_REVIEW_REQUIRED\",\"existingProjectId\":1394}]}}";
        ObjectNode report = service.validate(manifest(""));
        assertThat(report.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(report.path("duplicateStatus").asText()).isEqualTo("MATCH");
        assertThat(toolCalls).containsExactly("get_template", "validate_properties");
        assertThat(toolCalls).doesNotContain("import_properties", "create_upload_session");
    }

    @Test
    void allGatesPassGivesValidated() throws Exception {
        validateBody = "{\"httpStatus\":200,\"valid\":true,\"imageValidation\":{\"valid\":true},"
                + "\"body\":{\"valid\":true,\"duplicateRows\":0,\"rows\":[{\"status\":\"VALID\",\"duplicateReasons\":[]}]}}";
        ObjectNode report = service.validate(manifest(",\"images\":[{\"projectName\":\"X\",\"type\":\"Logo\",\"url\":\"https://img.example.com/a.png\"}],"
                + "\"imageSourcePermission\":{\"confirmed\":true,\"source\":\"Builder supplied\"},\"permissionEvidence\":\"email 2026-11-20\""));
        assertThat(report.path("status").asText()).isEqualTo("VALIDATED");
        assertThat(report.path("failedGates").size()).isZero();
    }

    @Test
    void gatewayInputErrorIsBlockedWithReason() throws Exception {
        validateBody = "{\"httpStatus\":400,\"body\":{\"error\":\"INVALID_TOOL_INPUT\",\"message\":\"properties[0] has unknown key\"}}";
        ObjectNode report = service.validate(manifest(""));
        assertThat(report.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(report.path("duplicateStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(report.path("gatewayMessage").asText()).contains("unknown key");
    }

    @Test
    void badManifestIsRejectedBeforeAnyNetworkCall() throws Exception {
        assertThatThrownBy(() -> service.validate(mapper.readTree("{\"properties\":[]}")))
                .isInstanceOf(McpControllerService.InputException.class);
        assertThatThrownBy(() -> service.validate(manifest(",\"imageSourcePermission\":{\"confirmed\":true}")))
                .isInstanceOf(McpControllerService.InputException.class);
        assertThatThrownBy(() -> service.probe(0)).isInstanceOf(McpControllerService.InputException.class);
        assertThat(toolCalls).isEmpty();
    }

    @Test
    void wrongKeyGivesErrorWithoutLeakingUrl() {
        McpControllerProperties props = new McpControllerProperties();
        props.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/property-automation/mcp/wrong-key-0123456789abcdef");
        ObjectNode report = new McpControllerService(props, mapper).probe(1394);
        assertThat(report.path("status").asText()).isEqualTo("ERROR");
        assertThat(report.path("message").asText()).contains("404");
        assertThat(report.toString()).doesNotContain("wrong-key");
    }
}
