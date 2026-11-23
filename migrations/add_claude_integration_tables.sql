-- Claude property connector: new tables only.
-- No existing table is altered. No constraint, index or column is added to `projects`, `builders`
-- or any other existing table. With spring.jpa.hibernate.ddl-auto=update these are created
-- automatically; run this script manually where ddl-auto is not used.
-- Compatible with MySQL 8 and MariaDB 10.4+.

CREATE TABLE IF NOT EXISTS claude_integration_job (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    job_id                   VARCHAR(36)  NOT NULL,
    batch_name               VARCHAR(200) NULL,
    idempotency_key          VARCHAR(200) NULL,
    content_hash             VARCHAR(140) NOT NULL,
    submitted_by_user_id     INT          NULL,
    submitted_by_email       VARCHAR(255) NULL,
    original_excel_filename  VARCHAR(255) NULL,
    original_zip_filename    VARCHAR(255) NULL,
    allow_partial            BIT(1)       NOT NULL DEFAULT b'0',
    total_properties         INT          NOT NULL DEFAULT 0,
    successful_properties    INT          NOT NULL DEFAULT 0,
    failed_properties        INT          NOT NULL DEFAULT 0,
    duplicate_properties     INT          NOT NULL DEFAULT 0,
    skipped_properties       INT          NOT NULL DEFAULT 0,
    pending_properties       INT          NOT NULL DEFAULT 0,
    job_status               VARCHAR(32)  NOT NULL,
    error_summary            TEXT         NULL,
    validation_report_json   LONGTEXT     NULL,
    created_at               DATETIME(6)  NOT NULL,
    started_at               DATETIME(6)  NULL,
    completed_at             DATETIME(6)  NULL,
    updated_at               DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_claude_job_job_id UNIQUE (job_id),
    -- NULL for refused/failed jobs (MySQL allows many NULLs in a UNIQUE index).
    CONSTRAINT uk_claude_job_idempotency UNIQUE (idempotency_key),
    INDEX idx_claude_job_status (job_status),
    INDEX idx_claude_job_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS claude_integration_job_item (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    job_ref_id             BIGINT       NOT NULL,
    excel_row              INT          NOT NULL,
    source_reference       VARCHAR(100) NULL,
    project_name           VARCHAR(255) NULL,
    builder_name           VARCHAR(255) NULL,
    city_name              VARCHAR(255) NULL,
    rera_no                VARCHAR(255) NULL,
    predicted_slug         VARCHAR(255) NULL,
    project_id             INT          NULL,   -- plain column, no FK to projects
    existing_project_id    INT          NULL,
    builder_id             INT          NULL,
    builder_created        BIT(1)       NOT NULL DEFAULT b'0',
    item_status            VARCHAR(40)  NOT NULL,
    is_draft               BIT(1)       NULL,
    verified               BIT(1)       NOT NULL DEFAULT b'0',
    images_expected        INT          NOT NULL DEFAULT 0,
    images_imported        INT          NOT NULL DEFAULT 0,
    errors_json            TEXT         NULL,
    warnings_json          TEXT         NULL,
    unapplied_fields_json  LONGTEXT     NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_claude_item_job (job_ref_id),
    INDEX idx_claude_item_project (project_id),
    CONSTRAINT fk_claude_item_job FOREIGN KEY (job_ref_id) REFERENCES claude_integration_job (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Database-backed identity lock for concurrent imports (see ClaudeProjectClaim).
CREATE TABLE IF NOT EXISTS claude_integration_project_claim (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    claim_key   VARCHAR(500) NOT NULL,
    job_id      VARCHAR(36)  NOT NULL,
    excel_row   INT          NOT NULL,
    project_id  INT          NULL,
    builder_id  INT          NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_claude_claim_key UNIQUE (claim_key),
    INDEX idx_claude_claim_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Rollback (removes only the connector's own tables; created drafts stay in `projects`):
-- DROP TABLE IF EXISTS claude_integration_job_item;
-- DROP TABLE IF EXISTS claude_integration_project_claim;
-- DROP TABLE IF EXISTS claude_integration_job;
