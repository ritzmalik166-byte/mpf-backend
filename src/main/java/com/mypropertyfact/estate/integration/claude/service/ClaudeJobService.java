package com.mypropertyfact.estate.integration.claude.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeJobResponse;
import com.mypropertyfact.estate.integration.claude.dto.ClaudePropertyResult;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationReport;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJob;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJobItem;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeItemStatus;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeJobStatus;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import com.mypropertyfact.estate.integration.claude.model.ClaudeRowPlan;
import com.mypropertyfact.estate.integration.claude.repository.ClaudeIntegrationJobItemRepository;
import com.mypropertyfact.estate.integration.claude.repository.ClaudeIntegrationJobRepository;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import com.mypropertyfact.estate.integration.claude.validator.ClaudeBatchValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/** Persistence of jobs and per-row results. Every write commits on its own so progress survives restarts. */
@Service
@RequiredArgsConstructor
public class ClaudeJobService {

    public static final String API_BASE = "/api/v1/integrations/claude";

    private final ClaudeIntegrationJobRepository jobRepository;
    private final ClaudeIntegrationJobItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaudeIntegrationJob createReceived(String jobId, String contentHash, String idempotencyKey, Integer userId,
                                               String email, String excelName, String zipName, String batchName,
                                               boolean allowPartial) {
        ClaudeIntegrationJob job = new ClaudeIntegrationJob();
        job.setJobId(jobId);
        job.setContentHash(contentHash);
        job.setIdempotencyKey(idempotencyKey);
        job.setSubmittedByUserId(userId);
        job.setSubmittedByEmail(email);
        job.setOriginalExcelFilename(ClaudeText.truncate(excelName, 255));
        job.setOriginalZipFilename(ClaudeText.truncate(zipName, 255));
        job.setBatchName(ClaudeText.truncate(batchName, 200));
        job.setAllowPartial(allowPartial);
        job.setJobStatus(ClaudeJobStatus.RECEIVED);
        // A unique-key violation propagates; the caller checks (in a fresh transaction) whether it
        // was the idempotency key and answers 409 only in that case.
        return jobRepository.saveAndFlush(job);
    }

    @Transactional(readOnly = true)
    public boolean idempotencyKeyInUse(String idempotencyKey) {
        return idempotencyKey != null && jobRepository.findByIdempotencyKey(idempotencyKey).isPresent();
    }

    @Transactional(readOnly = true)
    public ClaudeIntegrationException conflict(String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "DUPLICATE_BATCH");
        body.put("message", "This batch was already submitted. Poll the existing job instead of resubmitting.");
        jobRepository.findByIdempotencyKey(idempotencyKey).ifPresent(existing -> {
            body.put("jobId", existing.getJobId());
            body.put("status", existing.getJobStatus().name());
            body.put("statusUrl", API_BASE + "/jobs/" + existing.getJobId());
        });
        return new ClaudeIntegrationException(HttpStatus.CONFLICT, "DUPLICATE_BATCH", (String) body.get("message"), body);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setStatus(String jobId, ClaudeJobStatus status) {
        ClaudeIntegrationJob job = require(jobId);
        job.setJobStatus(status);
        if (status == ClaudeJobStatus.PROCESSING && job.getStartedAt() == null) {
            job.setStartedAt(LocalDateTime.now());
        }
    }

    /**
     * Stores the validation outcome and one item per row. For VALIDATION_FAILED the idempotency key
     * is cleared so the corrected batch can be resubmitted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordValidation(String jobId, ClaudeBatchValidator.Result result, ClaudeJobStatus status) {
        ClaudeIntegrationJob job = require(jobId);
        job.setValidationReportJson(json(result.report));
        job.setJobStatus(status);
        boolean refused = status == ClaudeJobStatus.VALIDATION_FAILED;
        for (ClaudeRowPlan p : result.plans) {
            ClaudeIntegrationJobItem item = new ClaudeIntegrationJobItem();
            item.setJob(job);
            item.setExcelRow(p.excelRow);
            item.setSourceReference(ClaudeText.truncate(p.sourceReference, 100));
            item.setProjectName(ClaudeText.truncate(p.projectName, 255));
            item.setBuilderName(ClaudeText.truncate(p.builderName, 255));
            item.setCityName(ClaudeText.truncate(p.cityName, 255));
            item.setReraNo(ClaudeText.truncate(p.reraNo, 255));
            item.setPredictedSlug(ClaudeText.truncate(p.slug, 255));
            item.setBuilderId(p.builderId);
            item.setImagesExpected(p.images.size());
            item.setExistingProjectId(p.existingProjectId);
            List<ClaudeValidationIssue> errors = new ArrayList<>();
            List<ClaudeValidationIssue> warnings = new ArrayList<>();
            p.issues.forEach(i -> (i.isError() ? errors : warnings).add(i));
            switch (p.state()) {
                case BLOCKED -> item.setItemStatus(ClaudeItemStatus.SKIPPED_VALIDATION);
                case DUPLICATE -> {
                    item.setItemStatus(ClaudeItemStatus.DUPLICATE_REVIEW_REQUIRED);
                    p.duplicateReasons.forEach(r -> errors.add(ClaudeValidationIssue.error("DUPLICATE_REVIEW_REQUIRED", r).row(p.excelRow)));
                }
                case VALID -> {
                    if (refused) {
                        item.setItemStatus(ClaudeItemStatus.SKIPPED_VALIDATION);
                        warnings.add(ClaudeValidationIssue.warning("BATCH_REFUSED",
                                "This row is valid but the batch was refused because of blocking errors elsewhere "
                                        + "(submit with allowPartial=true to import valid rows only).").row(p.excelRow));
                    } else {
                        item.setItemStatus(ClaudeItemStatus.PENDING);
                    }
                }
            }
            item.setErrorsJson(json(errors));
            item.setWarningsJson(json(warnings));
            item.setUnappliedFieldsJson(p.unappliedFields.isEmpty() ? null : json(p.unappliedFields));
            itemRepository.save(item);
        }
        if (refused) {
            job.setIdempotencyKey(null);
            job.setCompletedAt(LocalDateTime.now());
            job.setErrorSummary(summary(result.report));
        }
        recount(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateItem(String jobId, int excelRow, Consumer<ClaudeIntegrationJobItem> change) {
        ClaudeIntegrationJob job = require(jobId);
        for (ClaudeIntegrationJobItem item : itemRepository.findByJob_IdOrderByExcelRowAsc(job.getId())) {
            if (item.getExcelRow() == excelRow) {
                change.accept(item);
            }
        }
        itemRepository.flush();
        recount(job);
    }

    /** Final status from the items. COMPLETED only when every row is a verified draft without errors. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaudeJobStatus finish(String jobId, String failureMessage) {
        ClaudeIntegrationJob job = require(jobId);
        List<ClaudeIntegrationJobItem> items = itemRepository.findByJob_IdOrderByExcelRowAsc(job.getId());
        for (ClaudeIntegrationJobItem item : items) {
            if (item.getItemStatus() == ClaudeItemStatus.PENDING) {
                item.setItemStatus(ClaudeItemStatus.FAILED);
                item.setErrorsJson(json(appendError(item.getErrorsJson(), "JOB_ABORTED",
                        failureMessage != null ? failureMessage : "Job ended before this row was processed")));
            }
        }
        recount(job);
        long created = items.stream().filter(i -> i.getItemStatus() == ClaudeItemStatus.CREATED_DRAFT).count();
        boolean allClean = !items.isEmpty() && items.stream().allMatch(i -> i.getItemStatus() == ClaudeItemStatus.CREATED_DRAFT
                && i.isVerified() && Boolean.TRUE.equals(i.getDraft()) && isEmptyJson(i.getErrorsJson()));
        ClaudeJobStatus status = allClean ? ClaudeJobStatus.COMPLETED
                : created > 0 ? ClaudeJobStatus.PARTIALLY_COMPLETED : ClaudeJobStatus.FAILED;
        job.setJobStatus(status);
        job.setCompletedAt(LocalDateTime.now());
        if (status == ClaudeJobStatus.FAILED) {
            job.setIdempotencyKey(null);
        }
        StringBuilder sb = new StringBuilder();
        if (failureMessage != null) {
            sb.append(failureMessage).append(". ");
        }
        sb.append("Created drafts: ").append(job.getSuccessfulProperties())
                .append(", duplicates: ").append(job.getDuplicateProperties())
                .append(", skipped (validation): ").append(job.getSkippedProperties())
                .append(", failed: ").append(job.getFailedProperties());
        job.setErrorSummary(ClaudeText.truncate(sb.toString(), 4000));
        return status;
    }

    @Transactional(readOnly = true)
    public ClaudeJobResponse response(String jobId) {
        return toResponse(require(jobId));
    }

    @Transactional(readOnly = true)
    public ClaudeIntegrationJob job(String jobId) {
        return require(jobId);
    }

    @Transactional(readOnly = true)
    public List<ClaudePropertyResult> results(String jobId) {
        ClaudeIntegrationJob job = require(jobId);
        List<ClaudePropertyResult> out = new ArrayList<>();
        for (ClaudeIntegrationJobItem i : itemRepository.findByJob_IdOrderByExcelRowAsc(job.getId())) {
            ClaudePropertyResult r = new ClaudePropertyResult();
            r.setExcelRow(i.getExcelRow());
            r.setSourceReference(i.getSourceReference());
            r.setProjectName(i.getProjectName());
            r.setBuilderName(i.getBuilderName());
            r.setCityName(i.getCityName());
            r.setReraNo(i.getReraNo());
            r.setProjectId(i.getProjectId());
            r.setExistingProjectId(i.getExistingProjectId());
            r.setSlug(i.getPredictedSlug());
            r.setDraft(i.getDraft());
            r.setVerified(i.isVerified());
            r.setBuilderId(i.getBuilderId());
            r.setBuilderCreated(i.isBuilderCreated());
            r.setImagesExpected(i.getImagesExpected());
            r.setImagesImported(i.getImagesImported());
            r.setImportStatus(i.getItemStatus().name());
            r.setErrors(issues(i.getErrorsJson()));
            r.setWarnings(issues(i.getWarningsJson()));
            r.setUnappliedFields(map(i.getUnappliedFieldsJson()));
            out.add(r);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ClaudeIntegrationJob> unfinished() {
        return jobRepository.findByJobStatusIn(List.of(ClaudeJobStatus.RECEIVED, ClaudeJobStatus.VALIDATING,
                ClaudeJobStatus.QUEUED, ClaudeJobStatus.PROCESSING));
    }

    public ClaudeJobResponse toResponse(ClaudeIntegrationJob j) {
        ClaudeJobResponse r = new ClaudeJobResponse();
        r.setJobId(j.getJobId());
        r.setBatchName(j.getBatchName());
        r.setStatus(j.getJobStatus().name());
        r.setAllowPartial(j.isAllowPartial());
        r.setTotalProperties(j.getTotalProperties());
        r.setSuccessfulProperties(j.getSuccessfulProperties());
        r.setFailedProperties(j.getFailedProperties());
        r.setDuplicateProperties(j.getDuplicateProperties());
        r.setSkippedProperties(j.getSkippedProperties());
        r.setPendingProperties(j.getPendingProperties());
        r.setOriginalExcelFilename(j.getOriginalExcelFilename());
        r.setOriginalZipFilename(j.getOriginalZipFilename());
        r.setCreatedAt(j.getCreatedAt());
        r.setStartedAt(j.getStartedAt());
        r.setCompletedAt(j.getCompletedAt());
        r.setErrorSummary(j.getErrorSummary());
        r.setStatusUrl(API_BASE + "/jobs/" + j.getJobId());
        r.setResultsUrl(API_BASE + "/jobs/" + j.getJobId() + "/results");
        return r;
    }

    private void recount(ClaudeIntegrationJob job) {
        List<ClaudeIntegrationJobItem> items = itemRepository.findByJob_IdOrderByExcelRowAsc(job.getId());
        int ok = 0, failed = 0, dup = 0, skipped = 0, pending = 0;
        for (ClaudeIntegrationJobItem i : items) {
            switch (i.getItemStatus()) {
                case CREATED_DRAFT -> ok++;
                case FAILED -> failed++;
                case DUPLICATE_REVIEW_REQUIRED -> dup++;
                case SKIPPED_VALIDATION -> skipped++;
                case PENDING -> pending++;
            }
        }
        job.setTotalProperties(items.size());
        job.setSuccessfulProperties(ok);
        job.setFailedProperties(failed);
        job.setDuplicateProperties(dup);
        job.setSkippedProperties(skipped);
        job.setPendingProperties(pending);
    }

    private ClaudeIntegrationJob require(String jobId) {
        return jobRepository.findByJobId(jobId).orElseThrow(() ->
                new ClaudeIntegrationException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "No job with id " + ClaudeText.truncate(jobId, 40)));
    }

    private static String summary(ClaudeValidationReport report) {
        return "Validation failed: " + report.getErrorCount() + " error(s), " + report.getDuplicateRows()
                + " duplicate row(s), " + report.getBlockedRows() + " blocked row(s). See /properties/validate output or the job results.";
    }

    public String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    public List<ClaudeValidationIssue> issues(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ClaudeValidationIssue>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> map(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public List<ClaudeValidationIssue> appendError(String existingJson, String code, String message) {
        List<ClaudeValidationIssue> list = issues(existingJson);
        list.add(ClaudeValidationIssue.error(code, message));
        return list;
    }

    private boolean isEmptyJson(String json) {
        return issues(json).isEmpty();
    }
}
