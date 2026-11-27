package com.mypropertyfact.estate.integration.mcpcontroller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fail-closed checks on the validate_properties response (ported from MPF_Java_SpringBoot_Controller Gates).
 * Unknown or missing data => NOT approved.
 *
 * <p>The importer's body is this backend's own {@code ClaudeValidationReport}: valid, totalRows, importableRows,
 * blockedRows, duplicateRows, errorCount, warningCount, workbookIssues[], rows[] (status, existingProjectId,
 * duplicateReasons[], errors[], warnings[]).
 */
public final class McpGates {
    private McpGates() {}

    public enum DuplicateCheck { CLEAR, MATCH, UNKNOWN }

    private static final Set<String> DUPLICATE_COUNT_KEYS =
            Set.of("duplicates", "duplicatematches", "matchedduplicates", "duplicaterows");

    /**
     * MATCH if ANY duplicate signal is present (status DUPLICATE_REVIEW_REQUIRED, duplicateRows &gt; 0, a non-empty
     * duplicateReasons list, an existingProjectId). CLEAR only with no match signal AND an explicit "zero duplicates"
     * field. Otherwise UNKNOWN, which is never treated as clear.
     */
    public static DuplicateCheck duplicates(JsonNode node) {
        int[] signals = new int[2]; // [0] = match signals, [1] = explicit clear signals
        scan(node, signals);
        if (signals[0] > 0) return DuplicateCheck.MATCH;
        if (signals[1] > 0) return DuplicateCheck.CLEAR;
        return DuplicateCheck.UNKNOWN;
    }

    private static void scan(JsonNode node, int[] signals) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isTextual()) {
            if (node.asText().toUpperCase(Locale.ROOT).contains("DUPLICATE_REVIEW_REQUIRED")) signals[0]++;
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) scan(item, signals);
            return;
        }
        if (!node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            String key = f.getKey().replace("_", "").toLowerCase(Locale.ROOT);
            JsonNode val = f.getValue();
            if (DUPLICATE_COUNT_KEYS.contains(key)) {
                if (val.isArray()) signals[val.isEmpty() ? 1 : 0]++;
                else if (val.isIntegralNumber()) signals[val.asInt() == 0 ? 1 : 0]++;
            } else if (key.equals("duplicatereasons") && val.isArray() && !val.isEmpty()) {
                signals[0]++;
            } else if (key.equals("existingprojectid") && val.isNumber()) {
                signals[0]++;
            }
            scan(val, signals);
        }
    }

    /** true only when the gateway AND the importer explicitly said valid and nothing failed. */
    public static boolean accepted(JsonNode toolResponse) {
        if (toolResponse == null || !toolResponse.isObject()) return false;
        if (toolResponse.path("httpStatus").asInt(200) >= 400) return false;
        JsonNode body = toolResponse.path("body");
        if (toolResponse.path("valid").isBoolean() && !toolResponse.path("valid").asBoolean()) return false;
        if (body.path("valid").isBoolean() && !body.path("valid").asBoolean()) return false;
        if (toolResponse.path("imageValidation").path("valid").isBoolean()
                && !toolResponse.path("imageValidation").path("valid").asBoolean()) return false;
        if (toolResponse.path("valid").isBoolean()) return toolResponse.path("valid").asBoolean();
        return body.path("valid").isBoolean() && body.path("valid").asBoolean();
    }

    /** Counts and issue codes only (no project content, no URLs, no messages), so the report cannot leak data. */
    public static ObjectNode summary(JsonNode toolResponse, ObjectMapper mapper) {
        ObjectNode s = mapper.createObjectNode();
        s.put("httpStatus", toolResponse.path("httpStatus").asInt(0));
        JsonNode body = toolResponse.path("body");
        for (String k : new String[]{"totalRows", "importableRows", "blockedRows", "duplicateRows", "errorCount", "warningCount"}) {
            if (body.path(k).isNumber()) s.put(k, body.path(k).asInt());
        }
        if (body.path("error").isTextual()) s.put("gatewayError", body.path("error").asText());
        JsonNode img = toolResponse.path("imageValidation");
        if (img.isObject()) {
            ObjectNode i = s.putObject("images");
            if (img.path("valid").isBoolean()) i.put("valid", img.path("valid").asBoolean());
            JsonNode sum = img.path("summary");
            for (String k : new String[]{"images", "invalid", "specMatched", "resizeRequired", "missingAssets", "errors", "warnings"}) {
                if (sum.path(k).isNumber()) i.put(k, sum.path(k).asInt());
            }
        }
        TreeSet<String> errorCodes = new TreeSet<>();
        TreeSet<String> warningCodes = new TreeSet<>();
        collectCodes(body.path("workbookIssues"), errorCodes, warningCodes);
        for (JsonNode row : body.path("rows")) {
            collectCodes(row.path("errors"), errorCodes, errorCodes);
            collectCodes(row.path("warnings"), warningCodes, warningCodes);
            if (row.path("status").isTextual()) s.withArray("rowStatuses").add(row.path("status").asText());
        }
        errorCodes.forEach(s.putArray("errorCodes")::add);
        warningCodes.forEach(s.putArray("warningCodes")::add);
        return s;
    }

    private static void collectCodes(JsonNode issues, Set<String> errors, Set<String> warnings) {
        if (!issues.isArray()) return;
        for (JsonNode issue : issues) {
            if (!issue.path("code").isTextual()) continue;
            String sev = issue.path("severity").asText("ERROR").toUpperCase(Locale.ROOT);
            (sev.startsWith("WARN") ? warnings : errors).add(issue.path("code").asText());
        }
    }
}
