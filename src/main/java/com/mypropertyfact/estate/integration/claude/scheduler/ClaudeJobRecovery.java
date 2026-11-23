package com.mypropertyfact.estate.integration.claude.scheduler;

import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJob;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeJobStatus;
import com.mypropertyfact.estate.integration.claude.service.ClaudeImportJobRunner;
import com.mypropertyfact.estate.integration.claude.service.ClaudeJobService;
import com.mypropertyfact.estate.integration.claude.storage.ClaudeTemporaryStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

/**
 * After a restart: QUEUED jobs whose files are still on disk are queued again; jobs that were
 * mid-validation or mid-processing are closed with an explicit "interrupted" status. Rows already
 * created stay as drafts and remain listed in the job results.
 */
@Slf4j
@Component
public class ClaudeJobRecovery {

    private final ClaudeJobService jobService;
    private final ClaudeImportJobRunner runner;
    private final ClaudeTemporaryStorageService storage;
    private final ThreadPoolTaskExecutor executor;

    public ClaudeJobRecovery(ClaudeJobService jobService, ClaudeImportJobRunner runner, ClaudeTemporaryStorageService storage,
                             @Qualifier("claudeImportExecutor") ThreadPoolTaskExecutor executor) {
        this.jobService = jobService;
        this.runner = runner;
        this.storage = storage;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            for (ClaudeIntegrationJob job : jobService.unfinished()) {
                String id = job.getJobId();
                boolean filesPresent = Files.exists(storage.jobDir(id).resolve(ClaudeTemporaryStorageService.EXCEL_FILE));
                if (job.getJobStatus() == ClaudeJobStatus.QUEUED && filesPresent) {
                    log.info("Re-queueing Claude job {} after restart", id);
                    executor.execute(() -> runner.run(id));
                } else {
                    jobService.finish(id, "Interrupted by an application restart");
                    storage.deleteDir(storage.jobDir(id));
                }
            }
        } catch (Exception e) {
            // The connector tables may not exist yet (e.g. ddl-auto=none before the migration ran).
            log.warn("Claude job recovery skipped: {}", e.getMessage());
        }
    }
}
