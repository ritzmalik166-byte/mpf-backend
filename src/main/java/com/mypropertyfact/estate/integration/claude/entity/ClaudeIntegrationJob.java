package com.mypropertyfact.estate.integration.claude.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One Claude batch (one workbook + one optional image ZIP). Never stores passwords or tokens. */
@Entity
@Getter
@Setter
@Table(name = "claude_integration_job",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_claude_job_job_id", columnNames = "job_id"),
                @UniqueConstraint(name = "uk_claude_job_idempotency", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_claude_job_status", columnList = "job_status"),
                @Index(name = "idx_claude_job_created", columnList = "created_at")
        })
public class ClaudeIntegrationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, length = 36)
    private String jobId;

    @Column(name = "batch_name", length = 200)
    private String batchName;

    /**
     * Set only while the job can still create or has created projects, so the same files cannot be
     * processed twice. Cleared (NULL) for VALIDATION_FAILED / FAILED jobs so a corrected retry works.
     */
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "content_hash", nullable = false, length = 140)
    private String contentHash;

    @Column(name = "submitted_by_user_id")
    private Integer submittedByUserId;

    @Column(name = "submitted_by_email", length = 255)
    private String submittedByEmail;

    @Column(name = "original_excel_filename", length = 255)
    private String originalExcelFilename;

    @Column(name = "original_zip_filename", length = 255)
    private String originalZipFilename;

    @Column(name = "allow_partial", nullable = false)
    private boolean allowPartial;

    @Column(name = "total_properties", nullable = false)
    private int totalProperties;

    @Column(name = "successful_properties", nullable = false)
    private int successfulProperties;

    @Column(name = "failed_properties", nullable = false)
    private int failedProperties;

    @Column(name = "duplicate_properties", nullable = false)
    private int duplicateProperties;

    @Column(name = "skipped_properties", nullable = false)
    private int skippedProperties;

    @Column(name = "pending_properties", nullable = false)
    private int pendingProperties;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status", nullable = false, length = 32)
    private ClaudeJobStatus jobStatus;

    @Lob
    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    /** Full validation report captured at submission, as JSON. */
    @Lob
    @Column(name = "validation_report_json", columnDefinition = "LONGTEXT")
    private String validationReportJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
