package com.mypropertyfact.estate.integration.mcpcontroller.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mypropertyfact.estate.integration.mcpcontroller.config.McpControllerProperties;
import com.mypropertyfact.estate.integration.mcpcontroller.service.McpControllerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin endpoints for the read-only MCP automation controller.
 *
 * <p>Path is under {@code /api/v1/admin/**}, which SecurityConfiguration already limits to ADMIN / SUPERADMIN;
 * each endpoint also needs MANAGE_PROJECTS. Nothing here imports, edits or publishes a project.
 *
 * <pre>
 * GET  /api/v1/admin/mcp-controller/status
 * POST /api/v1/admin/mcp-controller/probe?propertyId=1394
 * POST /api/v1/admin/mcp-controller/validate     body = manifest JSON (one project)
 * </pre>
 *
 * Completed runs answer 200 with {@code status} PASS / VALIDATED / BLOCKED / ERROR in the body.
 */
@RestController
@RequestMapping("/api/v1/admin/mcp-controller")
@RequiredArgsConstructor
public class McpControllerController {

    private static final String CAN = "@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')";

    private final McpControllerProperties props;
    private final McpControllerService service;
    private final ObjectMapper mapper;

    @GetMapping("/status")
    @PreAuthorize(CAN)
    public ResponseEntity<JsonNode> status() {
        if (!props.isEnabled()) return notFound();
        return ResponseEntity.ok(service.status());
    }

    @PostMapping("/probe")
    @PreAuthorize(CAN)
    public ResponseEntity<JsonNode> probe(@RequestParam(value = "propertyId", required = false) Integer propertyId) {
        if (!props.isEnabled()) return notFound();
        if (!service.isConfigured()) return notConfigured();
        return guarded(() -> service.probe(propertyId));
    }

    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(CAN)
    public ResponseEntity<JsonNode> validate(@RequestBody JsonNode manifest) {
        if (!props.isEnabled()) return notFound();
        if (!service.isConfigured()) return notConfigured();
        return guarded(() -> service.validate(manifest));
    }

    /* ------------------------------------------------------------------ helpers */

    private ResponseEntity<JsonNode> guarded(java.util.function.Supplier<ObjectNode> run) {
        try {
            return ResponseEntity.ok(run.get());
        } catch (McpControllerService.InputException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage());
        } catch (McpControllerService.BusyException e) {
            return error(HttpStatus.CONFLICT, "BUSY", e.getMessage());
        }
    }

    private ResponseEntity<JsonNode> notFound() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }

    private ResponseEntity<JsonNode> notConfigured() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED",
                "MCP controller is not configured: set the MPF_MCP_URL environment variable on the backend and restart.");
    }

    private ResponseEntity<JsonNode> error(HttpStatus status, String code, String message) {
        ObjectNode body = mapper.createObjectNode();
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
