package com.mypropertyfact.estate.integration.claude.service;

import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeImportResponse;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationReport;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeJobStatus;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import com.mypropertyfact.estate.integration.claude.storage.ClaudeTemporaryStorageService;
import com.mypropertyfact.estate.integration.claude.storage.ClaudeTemporaryStorageService.StoredFile;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import com.mypropertyfact.estate.integration.claude.validator.ClaudeBatchValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/** Entry point for the controller: validate, submit, and hand jobs to the background runner. */
@Slf4j
@Service
public class ClaudeIntegrationService {

    private final ClaudeIntegrationProperties props;
    private final ClaudeTemporaryStorageService storage;
    private final ClaudeBatchValidator validator;
    private final ClaudeJobService jobService;
    private final ClaudeImportJobRunner runner;
    private final ThreadPoolTaskExecutor executor;

    public ClaudeIntegrationService(ClaudeIntegrationProperties props, ClaudeTemporaryStorageService storage,
                                    ClaudeBatchValidator validator, ClaudeJobService jobService, ClaudeImportJobRunner runner,
                                    @Qualifier("claudeImportExecutor") ThreadPoolTaskExecutor executor) {
        this.props = props;
        this.storage = storage;
        this.validator = validator;
        this.jobService = jobService;
        this.runner = runner;
        this.executor = executor;
    }

    /** Dry run. Creates no job and writes nothing to the database. */
    public ClaudeValidationReport validate(MultipartFile excelFile, MultipartFile imagesZip) {
        Path dir = storage.createDir("validate-" + UUID.randomUUID());
        try {
            Staged s = stage(excelFile, imagesZip, dir);
            return validator.validate(Files.readAllBytes(s.excel.path()), s.excel.originalFilename(),
                    s.zip == null ? null : s.zip.path(), s.zip == null ? null : s.zip.originalFilename()).report;
        } catch (ClaudeIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new ClaudeIntegrationException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Files could not be validated");
        } finally {
            storage.deleteDir(dir);
        }
    }

    public ClaudeImportResponse submit(MultipartFile excelFile, MultipartFile imagesZip, String batchName, boolean dryRun,
                                       boolean allowPartial, String clientIdempotencyKey, User user) {
        if (dryRun) {
            ClaudeImportResponse r = new ClaudeImportResponse();
            r.setDryRun(true);
            r.setStatus(null);
            r.setValidation(validate(excelFile, imagesZip));
            r.setMessage(r.getValidation().isValid() ? "Dry run passed; nothing was written." : "Dry run found problems; nothing was written.");
            return r;
        }
        String jobId = UUID.randomUUID().toString();
        Path staging = storage.createDir("staging-" + jobId);
        boolean handedOff = false;
        try {
            Staged s = stage(excelFile, imagesZip, staging);
            String contentHash = "sha256:" + s.excel.sha256() + ":" + (s.zip == null ? "none" : s.zip.sha256());
            String key = ClaudeText.isBlank(clientIdempotencyKey)
                    ? contentHash
                    : "client:" + user.getId() + ":" + ClaudeText.truncate(clientIdempotencyKey.trim(), 150);

            // The unique idempotency index makes concurrent identical submissions fail fast here.
            if (jobService.idempotencyKeyInUse(key)) {
                throw jobService.conflict(key);
            }
            try {
                jobService.createReceived(jobId, contentHash, key, user.getId(), user.getEmail(),
                        s.excel.originalFilename(), s.zip == null ? null : s.zip.originalFilename(), batchName, allowPartial);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (jobService.idempotencyKeyInUse(key)) {
                    throw jobService.conflict(key);
                }
                throw e;
            }
            jobService.setStatus(jobId, ClaudeJobStatus.VALIDATING);

            ClaudeBatchValidator.Result result = validator.validate(Files.readAllBytes(s.excel.path()), s.excel.originalFilename(),
                    s.zip == null ? null : s.zip.path(), s.zip == null ? null : s.zip.originalFilename());
            ClaudeValidationReport report = result.report;
            boolean rowProblems = report.getBlockedRows() > 0 || report.getDuplicateRows() > 0;
            boolean refuse = result.batchBlocked || result.importable().isEmpty() || (rowProblems && !allowPartial);

            ClaudeImportResponse response = new ClaudeImportResponse();
            response.setJobId(jobId);
            response.setValidation(report);
            response.setStatusUrl(ClaudeJobService.API_BASE + "/jobs/" + jobId);
            response.setResultsUrl(ClaudeJobService.API_BASE + "/jobs/" + jobId + "/results");
            if (refuse) {
                jobService.recordValidation(jobId, result, ClaudeJobStatus.VALIDATION_FAILED);
                response.setStatus(ClaudeJobStatus.VALIDATION_FAILED.name());
                response.setMessage(result.batchBlocked ? "The workbook or ZIP has blocking errors; nothing was imported."
                        : result.importable().isEmpty() ? "No row can be imported; nothing was imported."
                        : "Some rows have blocking errors or are duplicates; nothing was imported. Fix them, or resubmit with allowPartial=true.");
                throw new ClaudeIntegrationException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", response.getMessage(), response);
            }

            jobService.recordValidation(jobId, result, ClaudeJobStatus.QUEUED);
            storage.move(staging, storage.jobDir(jobId));
            handedOff = true;
            try {
                executor.execute(() -> runner.run(jobId));
            } catch (TaskRejectedException e) {
                jobService.finish(jobId, "Import queue is full");
                storage.deleteDir(storage.jobDir(jobId));
                throw new ClaudeIntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_FULL",
                        "Too many Claude imports are queued. Try again in a few minutes.");
            }
            response.setStatus(ClaudeJobStatus.QUEUED.name());
            response.setMessage(result.importable().size() + " row(s) queued for draft creation. Poll statusUrl, then read resultsUrl.");
            return response;
        } catch (ClaudeIntegrationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Claude submission {} failed: {}", jobId, e.toString());
            try {
                jobService.finish(jobId, "Submission failed");
            } catch (Exception ignored) {
                // the job row may not exist yet
            }
            throw new ClaudeIntegrationException(HttpStatus.INTERNAL_SERVER_ERROR, "SUBMISSION_FAILED", "The batch could not be submitted");
        } finally {
            if (!handedOff) {
                storage.deleteDir(staging);
            }
        }
    }

    private record Staged(StoredFile excel, StoredFile zip) {
    }

    private Staged stage(MultipartFile excelFile, MultipartFile imagesZip, Path dir) {
        if (excelFile == null || excelFile.isEmpty()) {
            throw new ClaudeIntegrationException(HttpStatus.BAD_REQUEST, "EXCEL_REQUIRED", "excelFile is required");
        }
        String name = excelFile.getOriginalFilename() == null ? "" : excelFile.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw new ClaudeIntegrationException(HttpStatus.BAD_REQUEST, "EXCEL_EXTENSION", "excelFile must be .xlsx or .xls");
        }
        StoredFile excel = storage.store(excelFile, dir, ClaudeTemporaryStorageService.EXCEL_FILE, props.getMaxExcelBytes(), "excelFile");
        StoredFile zip = null;
        if (imagesZip != null && !imagesZip.isEmpty()) {
            String zn = imagesZip.getOriginalFilename() == null ? "" : imagesZip.getOriginalFilename().toLowerCase(Locale.ROOT);
            if (!zn.endsWith(".zip")) {
                throw new ClaudeIntegrationException(HttpStatus.BAD_REQUEST, "ZIP_EXTENSION", "imagesZip must be a .zip file");
            }
            zip = storage.store(imagesZip, dir, ClaudeTemporaryStorageService.ZIP_FILE, props.getMaxZipBytes(), "imagesZip");
            if (!isZip(zip.path())) {
                throw new ClaudeIntegrationException(HttpStatus.BAD_REQUEST, "ZIP_INVALID", "imagesZip is not a ZIP archive (content checked)");
            }
        }
        return new Staged(excel, zip);
    }

    private static boolean isZip(Path p) {
        try (var in = Files.newInputStream(p)) {
            byte[] b = in.readNBytes(4);
            return b.length == 4 && b[0] == 0x50 && b[1] == 0x4B && (b[2] == 0x03 || b[2] == 0x05) && (b[3] == 0x04 || b[3] == 0x06);
        } catch (Exception e) {
            return false;
        }
    }
}
