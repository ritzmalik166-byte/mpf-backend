package com.mypropertyfact.estate.integration.claude.controller;

import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeImportResponse;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeJobResponse;
import com.mypropertyfact.estate.integration.claude.dto.ClaudePropertyResult;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationReport;
import com.mypropertyfact.estate.integration.claude.security.ClaudeIntegrationAccessService;
import com.mypropertyfact.estate.integration.claude.security.ClaudeRateLimiter;
import com.mypropertyfact.estate.integration.claude.service.ClaudeIntegrationService;
import com.mypropertyfact.estate.integration.claude.service.ClaudeJobService;
import com.mypropertyfact.estate.integration.claude.service.ClaudePublishService;
import com.mypropertyfact.estate.integration.claude.service.ClaudeTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Claude property connector. All endpoints require the existing JWT login, the existing
 * MANAGE_PROJECTS permission and the connector's own checks in {@link ClaudeIntegrationAccessService}.
 * Projects created here are always unpublished drafts.
 */
@RestController
@RequestMapping(ClaudeJobService.API_BASE)
@RequiredArgsConstructor
public class ClaudeIntegrationController {

    private static final String CAN = "@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')";

    private final ClaudeIntegrationAccessService access;
    private final ClaudeRateLimiter rateLimiter;
    private final ClaudeIntegrationService integrationService;
    private final ClaudeJobService jobService;
    private final ClaudeTemplateService templateService;
    private final ClaudePublishService publishService;

    @PostMapping(value = "/properties/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CAN)
    public ResponseEntity<ClaudeValidationReport> validate(
            @RequestParam("excelFile") MultipartFile excelFile,
            @RequestParam(value = "imagesZip", required = false) MultipartFile imagesZip,
            Authentication authentication) {
        User user = access.requireIntegrationUser(authentication);
        rateLimiter.check("u" + user.getId());
        return ResponseEntity.ok(integrationService.validate(excelFile, imagesZip));
    }

    @PostMapping(value = "/properties/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CAN)
    public ResponseEntity<ClaudeImportResponse> importProperties(
            @RequestParam("excelFile") MultipartFile excelFile,
            @RequestParam(value = "imagesZip", required = false) MultipartFile imagesZip,
            @RequestParam(value = "batchName", required = false) String batchName,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestParam(value = "allowPartial", defaultValue = "false") boolean allowPartial,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            Authentication authentication) {
        User user = access.requireIntegrationUser(authentication);
        rateLimiter.check("u" + user.getId());
        ClaudeImportResponse r = integrationService.submit(excelFile, imagesZip, batchName, dryRun, allowPartial, idempotencyKey, user);
        return ResponseEntity.status(dryRun ? HttpStatus.OK : HttpStatus.ACCEPTED).body(r);
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize(CAN)
    public ResponseEntity<ClaudeJobResponse> job(@PathVariable String jobId, Authentication authentication) {
        access.requireIntegrationUser(authentication);
        return ResponseEntity.ok(jobService.response(jobId));
    }

    @GetMapping("/jobs/{jobId}/results")
    @PreAuthorize(CAN)
    public ResponseEntity<Map<String, Object>> results(@PathVariable String jobId, Authentication authentication) {
        access.requireIntegrationUser(authentication);
        ClaudeJobResponse job = jobService.response(jobId);
        List<ClaudePropertyResult> results = jobService.results(jobId);
        return ResponseEntity.ok(Map.of("job", job, "results", results));
    }

    @GetMapping("/template")
    @PreAuthorize(CAN)
    public ResponseEntity<?> template(@RequestParam(value = "format", defaultValue = "xlsx") String format,
                                      Authentication authentication) {
        access.requireIntegrationUser(authentication);
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok(templateService.describe());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mpf-claude-property-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(templateService.workbook());
    }

    /**
     * Publishes one unpublished draft that THIS integration account imported. Body must be {@code {"confirm": true}}.
     * Only {@code projects.status} changes. See {@link ClaudePublishService} for every rule and error code.
     */
    @PostMapping("/projects/{projectId}/publish")
    @PreAuthorize(CAN)
    public ResponseEntity<Map<String, Object>> publish(@PathVariable("projectId") String projectId,
                                                       @RequestBody(required = false) String body,
                                                       Authentication authentication) {
        User user = access.requireIntegrationUser(authentication);
        rateLimiter.check("u" + user.getId());
        int id = publishService.parseProjectId(projectId);
        publishService.requireConfirmation(body, id);
        return ResponseEntity.ok(publishService.publish(id, user));
    }
}
