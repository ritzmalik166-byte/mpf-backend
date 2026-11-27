package com.mypropertyfact.estate.integration.mcpcontroller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mypropertyfact.estate.integration.mcpcontroller.client.McpClient;
import com.mypropertyfact.estate.integration.mcpcontroller.config.McpControllerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-only MCP automation controller (ported from MPF_Java_SpringBoot_Controller ControllerRunner).
 *
 * <p>Calls the Next.js MCP gateway, which forwards to this backend's /integration endpoints. It never calls
 * itself directly. Only {@code get_template}, {@code get_property_details} and {@code validate_properties}
 * may be called. There is intentionally no import, upload, edit or publish code path.
 *
 * <p>Reports are sanitized: no MCP URL, no project text, no image URLs, no full server responses.
 */
@Slf4j
@Service
public class McpControllerService {

    public static final Set<String> REQUIRED_TOOLS = Set.of("get_template", "validate_properties", "get_property_details");
    /** The only tools this controller may call. Checked before every tools/call. */
    public static final Set<String> ALLOWED_CALLS = Set.of("get_template", "get_property_details", "validate_properties");

    private final McpControllerProperties props;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ObjectNode> lastRun = new AtomicReference<>();

    public McpControllerService(McpControllerProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** Thrown for bad caller input (manifest / property id). The controller maps it to HTTP 400. */
    public static class InputException extends RuntimeException {
        public InputException(String message) { super(message); }
    }

    /** Thrown when another run is in progress. The controller maps it to HTTP 409. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("Another MCP controller run is in progress. Try again shortly."); }
    }

    public boolean isConfigured() {
        return !props.resolveUrl().isBlank();
    }

    /** Status for the admin panel. Never includes the URL or key. */
    public ObjectNode status() {
        ObjectNode s = mapper.createObjectNode();
        s.put("enabled", props.isEnabled());
        s.put("configured", isConfigured());
        s.put("running", running.get());
        s.put("defaultPropertyId", props.getDefaultPropertyId());
        s.put("scheduledProbeEnabled", props.isScheduledProbeEnabled());
        if (props.isScheduledProbeEnabled()) s.put("scheduledProbeCron", props.getScheduledProbeCron());
        ObjectNode last = lastRun.get();
        if (last != null) {
            ObjectNode l = s.putObject("lastRun");
            for (String k : new String[]{"mode", "status", "startedAt", "endedAt", "message"}) {
                if (last.hasNonNull(k)) l.set(k, last.get(k));
            }
        }
        return s;
    }

    /** probe: initialize, tools/list, get_template, get_property_details. Status PASS or ERROR. */
    public ObjectNode probe(Integer propertyId) {
        int id = propertyId == null ? props.getDefaultPropertyId() : propertyId;
        if (id < 1) throw new InputException("propertyId must be a positive integer");
        return run("probe", (client, report, operations) -> {
            McpClient.ToolResult details = call(client, "get_property_details",
                    mapper.createObjectNode().put("propertyId", id).put("inspectImages", false), operations);
            report.put("propertyIdChecked", id);
            report.put("propertyReadHttpStatus", details.httpStatus());
            requireSuccess("get_property_details", details);
            report.put("propertyReadSucceeded", true);
            report.put("status", "PASS");
        });
    }

    /**
     * validate: one candidate project through validate_properties plus the four fail-closed gates.
     * Status VALIDATED (all gates passed), BLOCKED or ERROR. VALIDATED is not permission to import or publish.
     */
    public ObjectNode validate(JsonNode manifest) {
        // Input checks happen before any network call.
        if (manifest == null || !manifest.isObject()) throw new InputException("Manifest must be a JSON object");
        JsonNode properties = manifest.path("properties");
        if (!properties.isArray() || properties.size() != 1 || !properties.get(0).isObject())
            throw new InputException("Single-project pilot requires exactly one properties[] object");
        JsonNode perm = manifest.path("imageSourcePermission");
        boolean evidence = perm.path("confirmed").asBoolean(false) && manifest.path("permissionEvidence").isTextual()
                && !manifest.path("permissionEvidence").asText().isBlank();
        if (perm.path("confirmed").asBoolean(false) && !evidence)
            throw new InputException("Cannot claim image permission without permissionEvidence");
        boolean urls = manifest.path("images").isArray() && !manifest.path("images").isEmpty();
        boolean zip = manifest.path("imagesZipUploadId").isTextual() && !manifest.path("imagesZipUploadId").asText().isBlank();
        if (urls && zip) throw new InputException("Select images URLs OR imagesZipUploadId, not both");

        ObjectNode call = mapper.createObjectNode();
        call.set("properties", properties);
        call.put("imageSpecPolicy", "strict");
        if (urls) call.set("images", manifest.get("images"));
        if (zip) call.set("imagesZip", mapper.createObjectNode().put("uploadId", manifest.path("imagesZipUploadId").asText()));
        if (urls || zip) call.set("imageSourcePermission", mapper.createObjectNode()
                .put("confirmed", perm.path("confirmed").asBoolean(false))
                .put("source", perm.path("source").asText("Unconfirmed")));

        return run("validate", (client, report, operations) -> {
            McpClient.ToolResult validation = call(client, "validate_properties", call, operations);
            JsonNode v = validation.content();
            // A tool error (e.g. 400 INVALID_TOOL_INPUT, 422) is a BLOCKED result with reasons, not a crash.
            boolean accepted = !validation.isError() && McpGates.accepted(v);
            McpGates.DuplicateCheck dupe = McpGates.duplicates(v.path("body"));
            boolean clear = dupe == McpGates.DuplicateCheck.CLEAR;
            boolean imagesProvided = urls || zip;

            report.put("responseValid", accepted);
            report.put("duplicateStatus", dupe.name());
            report.set("validationSummary", McpGates.summary(v, mapper));
            if (validation.isError() && v.path("body").path("message").isTextual()) {
                // Written by our own gateway (no secrets); truncated defensively.
                String m = v.path("body").path("message").asText();
                report.put("gatewayMessage", m.length() > 300 ? m.substring(0, 300) : m);
            }
            ObjectNode gates = report.putObject("gates");
            gates.put("permissionEvidence", evidence);
            gates.put("duplicateClear", clear);
            gates.put("validationAccepted", accepted);
            gates.put("imageTransferIncluded", imagesProvided);
            ArrayNode failed = report.putArray("failedGates");
            gates.fields().forEachRemaining(g -> { if (!g.getValue().asBoolean()) failed.add(g.getKey()); });
            report.put("status", failed.isEmpty() ? "VALIDATED" : "BLOCKED");
        });
    }

    /* ------------------------------------------------------------------ shared run frame */

    @FunctionalInterface
    private interface Step {
        void apply(McpClient client, ObjectNode report, ArrayNode operations) throws Exception;
    }

    private ObjectNode run(String mode, Step step) {
        if (!running.compareAndSet(false, true)) throw new BusyException();
        ObjectNode report = mapper.createObjectNode();
        report.put("startedAt", Instant.now().toString());
        report.put("mode", mode);
        report.put("writePerformed", false);
        ArrayNode operations = report.putArray("operations");
        try {
            McpClient client = new McpClient(props.resolveUrl(), mapper);
            JsonNode init = client.initialize();
            ObjectNode server = report.putObject("server");
            server.put("name", init.path("serverInfo").path("name").asText(""));
            server.put("version", init.path("serverInfo").path("version").asText(""));
            server.put("protocolVersion", init.path("protocolVersion").asText(""));
            operations.add("initialize");

            JsonNode toolList = client.listTools();
            operations.add("tools/list");
            if (!toolList.isArray()) throw new IllegalStateException("tools/list did not return an array");
            SortedSet<String> names = new TreeSet<>();
            for (JsonNode item : toolList) if (item.hasNonNull("name")) names.add(item.path("name").asText());
            names.forEach(report.putArray("availableTools")::add);
            SortedSet<String> missing = new TreeSet<>(REQUIRED_TOOLS);
            missing.removeAll(names);
            missing.forEach(report.putArray("missingRequiredTools")::add);
            if (!missing.isEmpty()) throw new IllegalStateException("Required MCP tools missing: " + missing);

            McpClient.ToolResult template = call(client, "get_template", mapper.createObjectNode(), operations);
            requireSuccess("get_template", template);
            report.put("templateLoaded", true);

            step.apply(client, report, operations);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markError(report, "MCP_OR_VALIDATION", "MCP run was interrupted");
        } catch (Exception e) {
            markError(report, e instanceof IllegalArgumentException ? "CONFIGURATION" : "MCP_OR_VALIDATION", safeError(e));
        } finally {
            report.put("endedAt", Instant.now().toString());
            lastRun.set(report.deepCopy());
            running.set(false);
        }
        log.info("[mcp-controller] {} -> {}", mode, report.path("status").asText());
        return report;
    }

    private void markError(ObjectNode report, String category, String message) {
        report.put("status", "ERROR");
        report.put("errorCategory", category);
        report.put("message", message);
    }

    private static McpClient.ToolResult call(McpClient client, String tool, ObjectNode args, ArrayNode operations) throws Exception {
        if (!ALLOWED_CALLS.contains(tool)) throw new IllegalStateException("Tool not allowed in read-only controller: " + tool);
        McpClient.ToolResult r = client.callTool(tool, args);
        operations.add(tool);
        return r;
    }

    static void requireSuccess(String tool, McpClient.ToolResult result) {
        if (result.isError() || result.httpStatus() >= 400)
            throw new IllegalStateException("MCP tool " + tool + " returned HTTP " + result.httpStatus());
        if (result.content() == null || result.content().isNull() || result.content().isMissingNode())
            throw new IllegalStateException("MCP tool " + tool + " returned empty JSON");
    }

    static String safeError(Exception e) {
        // No raw messages from HTTP libraries, which can expose the secret path.
        if (e instanceof IllegalArgumentException) return "Invalid MCP configuration: " + e.getMessage();
        if (e instanceof java.net.http.HttpTimeoutException) return "MCP request timed out";
        if (e instanceof java.net.ConnectException) return "Unable to connect to MCP server (is Next.js running?)";
        if (e instanceof java.net.UnknownHostException) return "MCP DNS lookup failed";
        // IllegalStateException messages are written by this module and never contain the URL.
        if (e instanceof IllegalStateException) return e.getMessage();
        return "MCP operation failed; inspect server-side logs without exposing secrets";
    }
}
