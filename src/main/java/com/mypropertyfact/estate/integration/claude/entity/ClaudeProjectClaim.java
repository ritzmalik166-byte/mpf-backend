package com.mypropertyfact.estate.integration.claude.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Database-backed lock for concurrent Claude imports. Before a draft is created, its identity
 * keys ({@code SLUG:…}, {@code RERA:…}, {@code NBC:name|builder|city}, and {@code BUILDER:slug} for
 * a new builder) are inserted here in the same transaction as the project. The unique index on
 * {@code claim_key} makes a second, simultaneous import of the same identity fail and roll back,
 * on any number of application instances. Claims of a rolled-back row disappear with it.
 *
 * <p>This table is new; no constraint is added to the existing {@code projects} table.
 */
@Entity
@Getter
@Setter
@Table(name = "claude_integration_project_claim",
        uniqueConstraints = @UniqueConstraint(name = "uk_claude_claim_key", columnNames = "claim_key"),
        indexes = @Index(name = "idx_claude_claim_project", columnList = "project_id"))
public class ClaudeProjectClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_key", nullable = false, length = 500)
    private String claimKey;

    @Column(name = "job_id", nullable = false, length = 36)
    private String jobId;

    @Column(name = "excel_row", nullable = false)
    private int excelRow;

    /** Project (or, for BUILDER: keys, builder) id once created. */
    @Column(name = "project_id")
    private Integer projectId;

    @Column(name = "builder_id")
    private Integer builderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
