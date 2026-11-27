package com.mypropertyfact.estate.integration.claude.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeItemStatus;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import com.mypropertyfact.estate.services.ListingActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;

/**
 * Publishes ONE unpublished draft that the calling integration account imported itself.
 *
 * <p>Rules (all must pass, otherwise nothing is written):
 * <ol>
 *   <li>The request body explicitly confirms: {@code {"confirm": true}} (400 CONFIRMATION_REQUIRED).</li>
 *   <li>The project exists (404 PROJECT_NOT_FOUND).</li>
 *   <li>It was created by a Claude import job submitted by THIS account: a job item with status CREATED_DRAFT for the
 *       project, whose job's {@code submitted_by_user_id} is the caller, plus the matching claim row
 *       (403 NOT_CREATED_BY_THIS_ACCOUNT). A project id alone is never trusted.</li>
 *   <li>It is still a draft (409 ALREADY_PUBLISHED).</li>
 *   <li>The import's post-import verification passed (422 PUBLISH_BLOCKED / IMPORT_NOT_VERIFIED), its identity fields are
 *       present, and no other PUBLISHED project uses the same slug (422 PUBLISH_BLOCKED / SLUG_CONFLICT).</li>
 * </ol>
 * Only {@code projects.status} changes, through one guarded statement ({@code ... WHERE id = ? AND status = 0}), so no other
 * column (not even {@code updated_at}) is touched and a concurrent second request cannot publish twice. The value is read
 * back before the transaction commits (500 PUBLISH_NOT_CONFIRMED rolls it back).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudePublishService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ListingActivityService listingActivityService;
    private final PlatformTransactionManager transactionManager;

    /** Parses the path id; anything that is not a positive integer is a 400, never a lookup. */
    public int parseProjectId(String raw) {
        try {
            int id = Integer.parseInt(raw == null ? "" : raw.trim());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw fail(HttpStatus.BAD_REQUEST, "INVALID_PROJECT_ID", "Project id must be a positive integer", null, null);
    }

    /** Publishing must be requested explicitly: body {"confirm": true}. */
    public void requireConfirmation(String body, int projectId) {
        boolean confirmed = false;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode n = objectMapper.readTree(body);
                confirmed = n != null && n.path("confirm").isBoolean() && n.path("confirm").asBoolean();
            } catch (Exception e) {
                throw fail(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body must be JSON: {\"confirm\": true}", projectId, null);
            }
        }
        if (!confirmed) {
            throw fail(HttpStatus.BAD_REQUEST, "CONFIRMATION_REQUIRED",
                    "Publishing must be confirmed explicitly with the body {\"confirm\": true}", projectId, null);
        }
    }

    @Transactional
    public Map<String, Object> publish(int projectId, User caller) {
        // 1. Exists (row locked until commit, so concurrent publishes of the same project are serialised).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, project_name, slugurl, status + 0 AS published, builder_id, city_id, project_status_id, property_type "
                        + "FROM projects WHERE id = ? FOR UPDATE", projectId);
        if (rows.isEmpty()) {
            throw fail(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found", projectId, null);
        }
        Map<String, Object> p = rows.get(0);

        // 2. Ownership: created by an import job submitted by THIS account, with the matching claim.
        List<Map<String, Object>> owned = jdbc.queryForList(
                "SELECT j.job_id, i.verified + 0 AS verified "
                        + "FROM claude_integration_job_item i JOIN claude_integration_job j ON j.id = i.job_ref_id "
                        + "WHERE i.project_id = ? AND i.item_status = ? AND j.submitted_by_user_id = ? "
                        + "AND EXISTS (SELECT 1 FROM claude_integration_project_claim c WHERE c.project_id = i.project_id AND c.job_id = j.job_id) "
                        + "ORDER BY i.id DESC",
                projectId, ClaudeItemStatus.CREATED_DRAFT.name(), caller.getId());
        if (owned.isEmpty()) {
            // Same answer whether the project is manual, admin-imported or imported by another account: no information leak.
            throw fail(HttpStatus.FORBIDDEN, "NOT_CREATED_BY_THIS_ACCOUNT",
                    "This project was not imported as a draft by this integration account", projectId, null);
        }
        String importJobId = String.valueOf(owned.get(0).get("job_id"));

        // 3. Still a draft.
        if (num(p.get("published")) != 0) {
            throw fail(HttpStatus.CONFLICT, "ALREADY_PUBLISHED", "Project is already published", projectId, null);
        }

        // 4. Eligibility.
        List<String> blockers = new ArrayList<>();
        if (num(owned.get(0).get("verified")) != 1) blockers.add("IMPORT_NOT_VERIFIED");
        for (String col : List.of("project_name", "slugurl", "builder_id", "city_id", "project_status_id", "property_type")) {
            Object v = p.get(col);
            if (v == null || String.valueOf(v).isBlank()) blockers.add("MISSING_" + col.toUpperCase(Locale.ROOT));
        }
        if (p.get("slugurl") != null) {
            Integer clash = jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE slugurl = ? AND id <> ? AND status = 1",
                    Integer.class, p.get("slugurl"), projectId);
            if (clash != null && clash > 0) blockers.add("SLUG_CONFLICT");
        }
        if (!blockers.isEmpty()) {
            throw fail(HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISH_BLOCKED",
                    "Project is not eligible for publishing: " + String.join(", ", blockers), projectId, blockers);
        }

        // 5. Change ONLY status, guarded against a concurrent publish.
        int updated = jdbc.update("UPDATE projects SET status = 1 WHERE id = ? AND status = 0", projectId);
        if (updated != 1) {
            throw fail(HttpStatus.CONFLICT, "ALREADY_PUBLISHED", "Project is already published", projectId, null);
        }

        // 6. Read back before commit; a failure rolls the update back.
        Integer now = jdbc.queryForObject("SELECT status + 0 FROM projects WHERE id = ?", Integer.class, projectId);
        if (now == null || now != 1) {
            throw fail(HttpStatus.INTERNAL_SERVER_ERROR, "PUBLISH_NOT_CONFIRMED", "Publish could not be confirmed; nothing was changed", projectId, null);
        }

        // 7. Audit trail AFTER commit, in its own REQUIRES_NEW transaction: it is written only for a publish that really
        //    committed, and a failing log write can never mark this transaction rollback-only. (Inside afterCommit the
        //    finished transaction is still bound to the thread, so a plain @Transactional call would join it and its
        //    insert would never be committed.)
        String detail = "Published via Claude integration (import job " + importJobId + ")";
        TransactionTemplate auditTx = new TransactionTemplate(transactionManager);
        auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Runnable audit = () -> {
            try {
                auditTx.executeWithoutResult(s -> listingActivityService.record(ListingActivityService.SOURCE_PROJECT,
                        (long) projectId, ListingActivityService.ACTION_PUBLISHED, caller, detail));
            } catch (Exception e) {
                log.warn("[claude-publish] activity log failed for project {}: {}", projectId, e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    audit.run();
                }
            });
        } else {
            audit.run();
        }
        log.info("[claude-publish] project {} published by integration user {}", projectId, caller.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isSuccess", 1);
        out.put("message", "Project published successfully");
        out.put("projectId", projectId);
        out.put("projectName", p.get("project_name"));
        out.put("slug", p.get("slugurl"));
        out.put("status", "PUBLISHED");
        out.put("published", true);
        out.put("publishedAt", Instant.now().toString());
        out.put("importJobId", importJobId);
        out.put("note", "The public page refreshes within about 2 minutes (website cache).");
        return out;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : o == null ? -1 : Integer.parseInt(String.valueOf(o));
    }

    /** MPF-style failure body ({isSuccess: 0, message, projectId}) plus the connector's machine-readable error code. */
    private static ClaudeIntegrationException fail(HttpStatus status, String code, String message, Integer projectId, List<String> reasons) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("isSuccess", 0);
        body.put("message", message);
        body.put("projectId", projectId);
        if (reasons != null) body.put("reasons", reasons);
        return new ClaudeIntegrationException(status, code, message, body);
    }
}
