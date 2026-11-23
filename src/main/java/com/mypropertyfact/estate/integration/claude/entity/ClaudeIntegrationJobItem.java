package com.mypropertyfact.estate.integration.claude.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Result for one Excel row of a job. {@code projectId} is a plain column (no FK) so the existing
 * {@code projects} table is never altered.
 */
@Entity
@Getter
@Setter
@Table(name = "claude_integration_job_item",
        indexes = {
                @Index(name = "idx_claude_item_job", columnList = "job_ref_id"),
                @Index(name = "idx_claude_item_project", columnList = "project_id")
        })
public class ClaudeIntegrationJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_ref_id", nullable = false)
    private ClaudeIntegrationJob job;

    @Column(name = "excel_row", nullable = false)
    private int excelRow;

    /** Value of the workbook's ID column. A reference number only, never a database id. */
    @Column(name = "source_reference", length = 100)
    private String sourceReference;

    @Column(name = "project_name", length = 255)
    private String projectName;

    @Column(name = "builder_name", length = 255)
    private String builderName;

    @Column(name = "city_name", length = 255)
    private String cityName;

    @Column(name = "rera_no", length = 255)
    private String reraNo;

    @Column(name = "predicted_slug", length = 255)
    private String predictedSlug;

    @Column(name = "project_id")
    private Integer projectId;

    @Column(name = "existing_project_id")
    private Integer existingProjectId;

    @Column(name = "builder_id")
    private Integer builderId;

    @Column(name = "builder_created", nullable = false)
    private boolean builderCreated;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 40)
    private ClaudeItemStatus itemStatus;

    /** Verified from the database after creation: true when projects.status = false. */
    @Column(name = "is_draft")
    private Boolean draft;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "images_expected", nullable = false)
    private int imagesExpected;

    @Column(name = "images_imported", nullable = false)
    private int imagesImported;

    @Lob
    @Column(name = "errors_json", columnDefinition = "TEXT")
    private String errorsJson;

    @Lob
    @Column(name = "warnings_json", columnDefinition = "TEXT")
    private String warningsJson;

    /** Populated values that could not be stored (e.g. BUILDER DESCRIPTION for an existing builder). */
    @Lob
    @Column(name = "unapplied_fields_json", columnDefinition = "LONGTEXT")
    private String unappliedFieldsJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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
