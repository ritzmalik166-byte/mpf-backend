package com.mypropertyfact.estate.integration.mcpcontroller.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stateless JSON-RPC 2.0 MCP transport for the My Property Fact Next.js MCP gateway
 * (POST /api/property-automation/mcp/{key}). Ported from MPF_Java_SpringBoot_Controller.
 * Never logs or echoes the credential-bearing URL.
 */
public final class McpClient {
    /** Plain http is accepted only for these hosts (local Next.js dev server). Everything else must be https. */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final URI endpoint;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final AtomicLong nextId = new AtomicLong();

    /** Result of tools/call: the gateway's structured JSON plus the MCP isError flag. */
    public record ToolResult(JsonNode content, boolean isError) {
        public int httpStatus() { return content.path("httpStatus").asInt(isError ? 500 : 200); }
    }

    public McpClient(String url, ObjectMapper mapper) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("MPF_MCP_URL is required");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MPF_MCP_URL is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean schemeOk = scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(host));
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!schemeOk || uri.getRawUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null
                || !path.contains("/api/property-automation/mcp/")
                || path.endsWith("/api/property-automation/mcp/")) {
            throw new IllegalArgumentException(
                    "MPF_MCP_URL must be https://<host>/api/property-automation/mcp/<key> (http only for localhost)");
        }
        this.endpoint = uri;
        this.mapper = mapper;
        // HTTP/1.1: the default (HTTP/2) sends an "Upgrade: h2c" request over plain http, which the Next.js
        // server drops (connection reset during initialize). The gateway needs nothing from HTTP/2.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode request(String method, JsonNode params, Duration timeout) throws IOException, InterruptedException {
        ObjectNode rpc = mapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", nextId.incrementAndGet());
        rpc.put("method", method);
        if (params != null) rpc.set("params", params);
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                // Streamable HTTP clients must accept both; the MPF gateway always answers with JSON.
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                // Skips the ngrok free-tier interstitial page when the gateway is reached through an ngrok tunnel.
                .header("ngrok-skip-browser-warning", "1")
                .header("User-Agent", "mpf-backend-mcp-controller/0.1.0")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpc))).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) throw new IllegalStateException("MCP HTTP status 404 (wrong MCP key or path)");
        if (res.statusCode() == 503) throw new IllegalStateException("MCP HTTP status 503 (gateway not configured on the server)");
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("MCP HTTP status " + res.statusCode());
        JsonNode payload;
        try {
            payload = mapper.readTree(res.body());
        } catch (IOException e) {
            // e.g. an HTML page from a tunnel or proxy instead of the gateway
            throw new IllegalStateException("MCP response is not JSON (check that MPF_MCP_URL reaches the Next.js gateway)");
        }
        if (payload.hasNonNull("error")) {
            // Do not echo untrusted error text: it could contain the configured URL / credentials.
            throw new IllegalStateException("MCP JSON-RPC error code " + payload.path("error").path("code").asText("unknown"));
        }
        if (!payload.has("result")) throw new IllegalStateException("MCP response has no result");
        return payload.get("result");
    }

    /** MCP handshake. Returns the server's initialize result (serverInfo, protocolVersion, capabilities). */
    public JsonNode initialize() throws IOException, InterruptedException {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2025-06-18");
        params.set("capabilities", mapper.createObjectNode());
        params.set("clientInfo", mapper.createObjectNode().put("name", "mpf-backend-mcp-controller").put("version", "0.1.0"));
        return request("initialize", params, Duration.ofSeconds(45));
    }

    public JsonNode listTools() throws IOException, InterruptedException {
        return request("tools/list", mapper.createObjectNode(), Duration.ofSeconds(45)).path("tools");
    }

    /**
     * Calls a tool and returns its structured JSON. A tool error (isError = true, e.g. HTTP 422 from the
     * importer's validation) is returned, not thrown, so the caller can apply fail-closed gates to it.
     */
    public ToolResult callTool(String name, ObjectNode arguments) throws IOException, InterruptedException {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        Duration timeout = name.equals("validate_properties") ? Duration.ofMinutes(10) : Duration.ofSeconds(60);
        JsonNode result = request("tools/call", params, timeout);
        boolean isError = result.path("isError").asBoolean(false);
        if (result.has("structuredContent") && result.get("structuredContent").isObject())
            return new ToolResult(result.get("structuredContent"), isError);
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asText()) && c.hasNonNull("text")) {
                try {
                    JsonNode parsed = mapper.readTree(c.path("text").asText());
                    if (parsed.isObject()) return new ToolResult(parsed, isError);
                } catch (Exception ignored) { /* might be explanatory text, keep searching */ }
            }
        }
        throw new IllegalStateException("MCP tool returned no structured JSON: " + name);
    }
}
