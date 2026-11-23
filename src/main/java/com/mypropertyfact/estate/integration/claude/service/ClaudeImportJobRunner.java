package com.mypropertyfact.estate.integration.claude.service;

import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJob;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJobItem;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeItemStatus;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeJobStatus;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeDuplicateException;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeDraftProjectImporter;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeImageAttachmentService;
import com.mypropertyfact.estate.integration.claude.model.ClaudeRowPlan;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeZipInspector;
import com.mypropertyfact.estate.integration.claude.repository.ClaudeIntegrationJobItemRepository;
import com.mypropertyfact.estate.integration.claude.storage.ClaudeTemporaryStorageService;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import com.mypropertyfact.estate.integration.claude.validator.ClaudeBatchValidator;
import com.mypropertyfact.estate.repositories.UserRepository;
import com.mypropertyfact.estate.services.ListingActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Background processing of one queued job. Re-validates the stored files against the current
 * database (anything may have changed since submission), then creates each valid row as a draft in
 * its own transaction, attaches its images, and verifies it from the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeImportJobRunner {

    private final ClaudeJobService jobService;
    private final ClaudeBatchValidator validator;
    private final ClaudeDraftProjectImporter importer;
    private final ClaudeImageAttachmentService imageService;
    private final ClaudeImportVerificationService verifier;
    private final ClaudeTemporaryStorageService storage;
    private final ClaudeIntegrationJobItemRepository itemRepository;
    private final ListingActivityService listingActivityService;
    private final UserRepository userRepository;

    public void run(String jobId) {
        Path dir = storage.jobDir(jobId);
        try {
            jobService.setStatus(jobId, ClaudeJobStatus.PROCESSING);
            ClaudeIntegrationJob job = jobService.job(jobId);
            Path excel = dir.resolve(ClaudeTemporaryStorageService.EXCEL_FILE);
            Path zipPath = dir.resolve(ClaudeTemporaryStorageService.ZIP_FILE);
            boolean hasZip = Files.exists(zipPath);
            if (!Files.exists(excel)) {
                jobService.finish(jobId, "Job files are missing (temporary storage was cleared)");
                return;
            }
            ClaudeBatchValidator.Result result = validator.validate(Files.readAllBytes(excel), job.getOriginalExcelFilename(),
                    hasZip ? zipPath : null, job.getOriginalZipFilename());
            if (result.batchBlocked) {
                jobService.finish(jobId, "The batch no longer passes workbook/ZIP validation");
                return;
            }
            Set<Integer> pendingRows = new HashSet<>();
            for (ClaudeIntegrationJobItem i : itemRepository.findByJob_IdOrderByExcelRowAsc(job.getId())) {
                if (i.getItemStatus() == ClaudeItemStatus.PENDING) {
                    pendingRows.add(i.getExcelRow());
                }
            }
            User actor = job.getSubmittedByUserId() == null ? null : userRepository.findById(job.getSubmittedByUserId()).orElse(null);
            try (ZipFile zip = hasZip ? ClaudeZipInspector.openZip(zipPath) : null) {
                for (ClaudeRowPlan plan : result.plans) {
                    if (pendingRows.contains(plan.excelRow)) {
                        processRow(job, plan, zip, actor);
                    }
                }
            }
            jobService.finish(jobId, null);
        } catch (Exception e) {
            log.error("Claude job {} failed: {}", jobId, e.toString());
            jobService.finish(jobId, "Job failed: " + ClaudeText.truncate(e.getClass().getSimpleName(), 100));
        } finally {
            storage.deleteDir(dir);
        }
    }

    private void processRow(ClaudeIntegrationJob job, ClaudeRowPlan plan, ZipFile zip, User actor) {
        String jobId = job.getJobId();
        switch (plan.state()) {
            case BLOCKED -> {
                List<ClaudeValidationIssue> errors = new ArrayList<>(plan.issues.stream().filter(ClaudeValidationIssue::isError).toList());
                errors.add(ClaudeValidationIssue.error("INVALID_AT_PROCESSING", "Row became invalid between submission and processing.").row(plan.excelRow));
                jobService.updateItem(jobId, plan.excelRow, i -> {
                    i.setItemStatus(ClaudeItemStatus.SKIPPED_VALIDATION);
                    i.setErrorsJson(jobService.json(errors));
                });
                return;
            }
            case DUPLICATE -> {
                List<ClaudeValidationIssue> errors = new ArrayList<>();
                plan.duplicateReasons.forEach(r -> errors.add(ClaudeValidationIssue.error("DUPLICATE_REVIEW_REQUIRED", r).row(plan.excelRow)));
                jobService.updateItem(jobId, plan.excelRow, i -> {
                    i.setItemStatus(ClaudeItemStatus.DUPLICATE_REVIEW_REQUIRED);
                    i.setExistingProjectId(plan.existingProjectId);
                    i.setErrorsJson(jobService.json(errors));
                });
                return;
            }
            default -> {
            }
        }

        ClaudeDraftProjectImporter.CreatedDraft created;
        try {
            if (!plan.staleClaimIds.isEmpty()) {
                importer.releaseStaleClaims(plan.staleClaimIds);
            }
            created = importer.createDraft(plan, job.getSubmittedByUserId(), jobId);
        } catch (ClaudeDuplicateException e) {
            jobService.updateItem(jobId, plan.excelRow, i -> {
                i.setItemStatus(ClaudeItemStatus.DUPLICATE_REVIEW_REQUIRED);
                i.setExistingProjectId(e.getExistingProjectId());
                i.setErrorsJson(jobService.json(jobService.appendError(i.getErrorsJson(), "DUPLICATE_REVIEW_REQUIRED", e.getMessage())));
            });
            return;
        } catch (Exception e) {
            log.warn("Claude job {} row {} failed: {}", jobId, plan.excelRow, e.toString());
            String msg = "Row import failed and was rolled back: " + rootMessage(e);
            jobService.updateItem(jobId, plan.excelRow, i -> {
                i.setItemStatus(ClaudeItemStatus.FAILED);
                i.setErrorsJson(jobService.json(jobService.appendError(i.getErrorsJson(), "ROW_FAILED", msg)));
            });
            return;
        }

        listingActivityService.record(ListingActivityService.SOURCE_PROJECT, (long) created.projectId(),
                ListingActivityService.ACTION_CREATED, actor,
                ClaudeText.truncate(plan.projectName + " (draft via Claude import, job " + jobId + ")", 480));

        ClaudeImageAttachmentService.AttachResult images = zip == null
                ? new ClaudeImageAttachmentService.AttachResult(0, List.of())
                : imageService.attach(jobId, created.projectId(), created.slug(), plan.projectName, plan.images, zip);
        ClaudeImportVerificationService.Verification v =
                verifier.verify(created.projectId(), plan, plan.images.size(), images.imported());

        List<String> problems = new ArrayList<>(images.errors());
        problems.addAll(v.problems());
        jobService.updateItem(jobId, plan.excelRow, i -> {
            i.setItemStatus(ClaudeItemStatus.CREATED_DRAFT);
            i.setProjectId(created.projectId());
            i.setPredictedSlug(created.slug());
            i.setBuilderId(created.builderId());
            i.setBuilderCreated(created.builderCreated());
            i.setImagesImported(images.imported());
            i.setDraft(v.draft());
            i.setVerified(v.ok());
            List<ClaudeValidationIssue> errs = jobService.issues(i.getErrorsJson());
            for (String p : problems) {
                errs.add(ClaudeValidationIssue.error("POST_IMPORT_CHECK", p).row(plan.excelRow));
            }
            i.setErrorsJson(jobService.json(errs));
        });
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return ClaudeText.truncate(m.replaceAll("[A-Za-z]:\\\\[^\\s]*|/[^\\s]*/[^\\s]*", "[path]"), 300);
    }
}
