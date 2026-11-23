# Claude Integration: Staging Deployment

Scope: **staging only.** Do not deploy to production as part of this handoff. Automatic publishing does not exist in this module, and nothing here enables it.

## 1. What is being deployed (all new files; no existing file changed)

```
migrations/add_claude_integration_tables.sql
src/main/java/com/mypropertyfact/estate/integration/claude/
  config/      ClaudeIntegrationConfig, ClaudeIntegrationProperties
  controller/  ClaudeIntegrationController
  dto/         ClaudeImportResponse, ClaudeJobResponse, ClaudePropertyResult, ClaudeValidationIssue, ClaudeValidationReport
  entity/      ClaudeIntegrationJob, ClaudeIntegrationJobItem, ClaudeProjectClaim, ClaudeJobStatus, ClaudeItemStatus
  exception/   ClaudeDuplicateException, ClaudeIntegrationException, ClaudeIntegrationExceptionHandler
  importer/    ClaudeDraftProjectImporter, ClaudeImageAttachmentService, ClaudeImageRowWriter
  model/       ClaudeParsedWorkbook, ClaudeRowPlan, ClaudeZipImage
  processor/   ClaudeWorkbookParser, ClaudeZipInspector
  repository/  ClaudeIntegrationJobRepository, ClaudeIntegrationJobItemRepository, ClaudeProjectClaimRepository
  scheduler/   ClaudeJobRecovery
  security/    ClaudeIntegrationAccessService, ClaudeIntegrationAccountRestrictionFilter, ClaudeRateLimiter
  service/     ClaudeImportJobRunner, ClaudeImportVerificationService, ClaudeIntegrationService, ClaudeJobService,
               ClaudeLookupService, ClaudeTemplateService
  spec/        ClaudeColumn, ClaudeImporterSpec
  storage/     ClaudeTemporaryStorageService
  util/        ClaudeText
  validator/   ClaudeBatchValidator
src/test/java/com/mypropertyfact/estate/integration/claude/  (8 test classes)
src/test/resources/claude/reference-property-list.xlsx
CLAUDE_INTEGRATION_*.md, CLAUDE_PROPERTY_WORKFLOW.md, CLAUDE_IMAGE_NAMING_SPEC.md
```

No new Maven dependency, and no change to `pom.xml`, `application*.properties`, security configuration, entities, repositories, controllers, services or the frontend.

## 2. Configuration (environment variables only)

| Variable | Default | Staging value |
|---|---|---|
| `CLAUDE_INTEGRATION_ENABLED` | `false` | `true` |
| `CLAUDE_INTEGRATION_ALLOWEDEMAILS` | *(empty = nobody)* | the integration account's email (comma-separated for several) |
| `CLAUDE_INTEGRATION_REQUIRELEASTPRIVILEGEACCOUNT` | `true` | keep `true` |
| `CLAUDE_INTEGRATION_RESTRICTINTEGRATIONACCOUNTS` | `true` | keep `true` |
| `CLAUDE_INTEGRATION_TEMPDIR` | `${java.io.tmpdir}/mpf-claude` | a private directory on the API host or container, e.g. `/var/mpf/claude-tmp` |
| `CLAUDE_INTEGRATION_RATELIMITPERMINUTE` | `10` | `10` |
| `CLAUDE_INTEGRATION_MAXEXCELBYTES` | `20971520` | |
| `CLAUDE_INTEGRATION_MAXZIPBYTES` | `314572800` | must be ≤ the 300 MB server/proxy limit |
| `CLAUDE_INTEGRATION_MAXEXTRACTEDBYTES` | `1073741824` | |
| `CLAUDE_INTEGRATION_MAXZIPENTRIES` | `2000` | |
| `CLAUDE_INTEGRATION_MAXROWS` | `500` | |
| `CLAUDE_INTEGRATION_STRICTLOCATIONADVANTAGE` | `false` | `true` makes unparseable LOCATION ADVANTAGE blocking |
| `CLAUDE_INTEGRATION_REQUIRECOREIMAGES` | `false` | `true` makes missing Desktop/Mobile/Thumbnail blocking |

Existing settings used unchanged: `upload_dir` (image files go to `${upload_dir}properties/<slug>/`), the JWT settings, and the datasource. The existing nginx snippet `deploy/nginx-proxy-body-size.conf` (`client_max_body_size 300m`) must be in place for `/api/`. Serve staging over **HTTPS** only.

Secrets: none are added by this module. Keep the integration account's password in the Claude agent's secret store, never in the repo. **Separately:** `application-dev.properties` currently contains real credentials (DB password, JWT secret, mail password). Rotate them and move them to environment variables (this needs a change to that existing file, so it was not done).

## 3. Database migration

- If staging runs with `spring.jpa.hibernate.ddl-auto=update` (the current dev setting), the three tables are created on start.
- Otherwise, run `migrations/add_claude_integration_tables.sql` once (MySQL 8 / MariaDB 10.4+). It is idempotent (`CREATE TABLE IF NOT EXISTS`).
- New tables: `claude_integration_job`, `claude_integration_job_item`, `claude_integration_project_claim`. **No existing table is altered.**
- **Fresh staging databases:** the existing `BlogStatusCheckConstraintRunner` fails on an empty schema (see the test report). Clone staging from an existing database dump, or fix that runner first (approval needed).

## 4. Integration account (Super Admin, in the existing admin panel)

1. Create a new staff user, for example `claude-importer@<your-domain>`, with role **ADMIN** (not SUPERADMIN), a strong password and a **dashboard username** (the admin login requires it).
2. Grant **exactly one permission: `MANAGE_PROJECTS`**. Do not leave the permissions empty: in the existing model, an ADMIN with no permission rows gets full CMS access, and the connector rejects such accounts with `ACCOUNT_TOO_PRIVILEGED`.
3. Approve or activate the account if your portal requires it.
4. Put the email in `CLAUDE_INTEGRATION_ALLOWEDEMAILS`.

With the restriction filter enabled, this account can only use `/api/v1/integrations/claude/**`, the auth session/refresh/logout endpoints and the two read-only admin project GETs. Its `MANAGE_PROJECTS` permission cannot be used to edit or delete projects through the existing APIs.

## 5. Deployment steps

1. Build: `./mvnw clean package` (or the existing Docker build; `-DskipTests` is fine because the tests were run separately, see the test report).
2. Set the environment variables (§2) on the staging API service.
3. Deploy the jar or image as usual.
4. Check: `GET /api/v1/integrations/claude/template?format=json` with the integration account's JWT returns `200`. Without a token it returns `401`. With another admin it returns `403`.
5. Smoke test: run `POST /properties/validate` with the reference workbook. You should get 20 rows, `valid: false`, with the amenity and status errors described in `CLAUDE_INTEGRATION_ANALYSIS.md` §0.
6. Import one small test property (1 row + 3 images). Confirm `COMPLETED`, `draft: true`, `verified: true`, that it is visible in the admin panel as unpublished, and that `GET /api/v1/projects/get/<slug>` does not return it.
7. Clean up the test draft through the admin panel with a normal admin account.

## 6. Endpoints (staging)

| Method | URL |
|---|---|
| POST | `https://<staging-api>/api/v1/admin-portal/auth/login` |
| POST | `https://<staging-api>/api/v1/integrations/claude/properties/validate` |
| POST | `https://<staging-api>/api/v1/integrations/claude/properties/import` |
| GET | `https://<staging-api>/api/v1/integrations/claude/jobs/{jobId}` |
| GET | `https://<staging-api>/api/v1/integrations/claude/jobs/{jobId}/results` |
| GET | `https://<staging-api>/api/v1/integrations/claude/template[?format=json]` |

## 7. Operations

- Logs: `Claude job …` lines (job id, row number, error class). No passwords, tokens or file contents are logged.
- Audit: each job stores the submitter (user id and email), file names, SHA-256 hashes, the full validation report and per-row results. Each created draft also gets a `listing_activity_event` row through the existing `ListingActivityService` ("draft via Claude import, job …").
- Temporary files: kept under `CLAUDE_INTEGRATION_TEMPDIR/<jobId>` only while a job is queued or running, then deleted. Validation-only uploads are deleted at the end of the request.
- A row's "identity claims" (`claude_integration_project_claim`) stay after a successful import. That is what blocks re-importing the same project. If an admin deletes a Claude-created draft, the next import detects the stale claim and releases it automatically.

## 8. Rollback

1. **Disable instantly:** set `CLAUDE_INTEGRATION_ENABLED=false` and restart. All connector endpoints return 404. Nothing else changes.
2. **Remove the code:** redeploy the previous build. The new files are self-contained, so no other code references them.
3. **Remove the data (optional):** run the `DROP TABLE` lines at the end of `migrations/add_claude_integration_tables.sql`. This only removes the connector's own tables.
4. **Drafts created by the connector stay** in `projects` (unpublished). To find them: `SELECT project_id FROM claude_integration_job_item WHERE item_status = 'CREATED_DRAFT'` (before step 3), or filter by `created_by_user_id` = the integration account. Review or delete them in the admin panel. The connector never deletes projects itself.
5. New builders created by the connector: `SELECT builder_id FROM claude_integration_job_item WHERE builder_created = 1`.

## 9. Known limitations

- **LOCATION ADVANTAGE as prose cannot be stored.** The existing schema only has benefit name + distance rows. The text is kept in the job result.
- **BUILDER DESCRIPTION for existing builders is never written** (by design). The text is kept in the job result.
- **Images are resized and re-encoded** into the existing boxes, the same as the admin importer. Exact-size originals are not preserved.
- **MySQL + filesystem are not atomic.** If image records fail after files are written, those files are removed, and the draft is still created and reported with a `POST_IMPORT_CHECK` error.
- **A human admin can race the connector.** If an admin creates the same project in the admin panel between the connector's in-transaction re-check and its commit, a duplicate can still occur. The window is milliseconds; Claude-vs-Claude races are fully prevented by the claims table.
- The rate limiter is in-memory per instance.
- The public `GET /api/v1/projects` (existing) returns all projects including drafts, with a `status` field. Hiding drafts there relies on the existing frontend. This is unchanged.
