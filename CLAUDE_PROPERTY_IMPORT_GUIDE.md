# Claude Property Import Guide (My Property Fact)

The single reference for any AI agent (Claude, Claude Code, others) or engineer who researches properties and uploads them to My Property Fact (MPF) through the Claude integration API.

> **Source of truth:** the Java code in `src/main/java/com/mypropertyfact/estate/integration/claude/` and its tests (60/60 passing, see `CLAUDE_INTEGRATION_TEST_REPORT.md`). Where the older docs differ from the code, this guide follows the code and lists the difference in [§12](#12-discrepancies-with-other-documents). Anything not confirmed in code or tests is marked **Needs verification**.

---

## Contents

1. [What the integration does](#1-what-the-integration-does)
2. [Data-safety guarantees](#2-data-safety-guarantees-non-negotiable)
3. [Authentication](#3-authentication)
4. [API reference](#4-api-reference)
5. [Excel workbook format](#5-excel-workbook-format)
6. [Images ZIP format](#6-images-zip-format)
7. [End-to-end workflow for Claude](#7-end-to-end-workflow-for-claude)
8. [Copy-paste API examples](#8-copy-paste-api-examples)
9. [Environment configuration](#9-environment-configuration)
10. [Errors and troubleshooting](#10-errors-and-troubleshooting)
11. [Reference: status values and issue codes](#11-reference-status-values-and-issue-codes)
12. [Discrepancies with other documents](#12-discrepancies-with-other-documents)

---

## 1. What the integration does

```
Research → Excel (32 columns) + images ZIP → login (JWT) → POST /properties/validate
        → POST /properties/import (async job) → GET /jobs/{id} until finished → GET /jobs/{id}/results
```

- It creates **new, unpublished draft projects only**, one per Excel row.
- It has its own importer. It does **not** call the admin panel's Excel uploader (`/api/v1/excel-upload/projects`), which publishes immediately and overwrites existing projects. **Never use that endpoint from an agent.**
- Every batch becomes a persistent **job** (database tables `claude_integration_job`, `claude_integration_job_item`). Jobs and results survive restarts.

Base path: **`/api/v1/integrations/claude`**

---

## 2. Data-safety guarantees (non-negotiable)

| Rule | How the code enforces it |
|---|---|
| Every new property is unpublished (`projects.status = false`) | `ClaudeDraftProjectImporter` builds a new `Project` with `setStatus(false)` before `persist`; no code in the module ever sets it to `true`. After the insert it reads `SELECT status FROM projects WHERE id=?` in the same transaction and rolls the row back if it is not a draft. Tests use a database trigger to confirm the INSERT itself writes `status = 0`. |
| Existing projects are never updated, deleted or published | The importer never loads an existing project for writing. It only inserts new rows (project, FAQs, floor plans, location benefits, walkthrough, about, amenity links). |
| Duplicates are flagged, not written | During validation and again inside the row transaction, the row is checked against existing projects by **slug**, **RERA NO** (case/space-insensitive) and **project name + builder + city**. A match → `DUPLICATE_REVIEW_REQUIRED`, nothing written, `existingProjectId` returned. |
| Concurrent imports cannot create the same project twice | Identity keys (`SLUG:…`, `RERA:…`, `NBC:name|builder|city`, `BUILDER:slug`) are inserted into `claude_integration_project_claim` (unique index) in the same transaction as the draft. A parallel import of the same identity fails and rolls back. |
| Existing builder profiles are never overwritten | An existing builder is only referenced. `BUILDER DESCRIPTION` is written **only** when the import creates the builder. |
| Images attach only to projects created by the same job | `ClaudeImageRowWriter` refuses a project unless this job's claims reference it **and** it is still a draft. ZIP images must belong to rows in the same workbook. |
| Excel `ID` is never a project identifier | `ID` is stored only as `sourceReference` in the job result. |
| No automatic publishing | There is no endpoint, flag or code path that publishes. A human publishes in the admin panel. |
| One bad row does not undo good rows | Each row runs in its own `REQUIRES_NEW` transaction. A failure rolls back that row only (including a builder it created). |

Known limit: if a human admin creates the same project in the admin panel during the milliseconds between the importer's in-transaction re-check and its commit, a duplicate is possible. Claude-vs-Claude races are fully prevented.

---

## 3. Authentication

There is **no API key and no custom header**. The integration reuses the existing admin login and JWT.

### 3.1 Two different things

| | What | Where it goes |
|---|---|---|
| **Login credentials** | email, password, dashboard username of the integration account | JSON **body** of `POST /api/v1/admin-portal/auth/login`, once |
| **JWT token** | `token` from the login response | `Authorization: Bearer <JWT_TOKEN>` header on every integration call |

Never send the password on integration calls. Never put credentials or tokens in prompts, logs, reports or files. Read them from environment variables or a secret store.

### 3.2 Login

```http
POST /api/v1/admin-portal/auth/login
Content-Type: application/json

{
  "email": "<INTEGRATION_EMAIL>",
  "password": "<INTEGRATION_PASSWORD>",
  "dashboardUsername": "<INTEGRATION_DASHBOARD_USERNAME>"
}
```

- `dashboardUsername` is **required for staff (ADMIN) accounts**. It must equal the username a Super Admin set in Manage Users. If it is missing: `401 {"message": "Dashboard username is required for admin login."}`. If it is wrong: `401 {"message": "Invalid dashboard username."}`.
- Bad password → `401 {"message": "…"}`.
- Success → `200`:

```json
{ "token": "<JWT_TOKEN>", "refreshToken": "<REFRESH_TOKEN>", "expiresIn": 86400000, "user": { "...": "account details" } }
```

The response also sets `token` and `refreshToken` cookies. Agents should use the `token` field with the Bearer header.

`/api/v1/auth/login` is an equivalent route (same handler). This guide uses `/api/v1/admin-portal/auth/login`.

### 3.3 Token lifetime and refresh

- Access-token lifetime = `security.jwt.expiration-time`: **86 400 000 ms (24 h)** in `application-dev.properties`. Staging/production values: **Needs verification**.
- **Recommended:** when an integration call returns `401`, log in again once and retry.
- Refresh endpoints exist: `POST /api/v1/admin-portal/auth/refresh` with body `{"refreshToken": "<REFRESH_TOKEN>"}` returns a new `{token, refreshToken, expiresIn, user}`. The security configuration marks this route as `authenticated()`, so it works only while the current access token is still valid. Behaviour with an expired access token: **Needs verification**. Re-login is the reliable path.

### 3.4 Who may call the integration

Each call passes these checks **in this order** (from `@PreAuthorize` + `ClaudeIntegrationAccessService`):

| # | Check | Failure |
|---|---|---|
| 1 | Valid, unexpired JWT | `401 UNAUTHORIZED` |
| 2 | Existing permission `MANAGE_PROJECTS` (`AdminPermissionService.can`) | `403 FORBIDDEN` |
| 3 | Integration enabled (`claude.integration.enabled=true`) | `404 NOT_FOUND` |
| 4 | Account email is in `claude.integration.allowed-emails` (case-insensitive) | `403 FORBIDDEN` |
| 5 | Least privilege (`require-least-privilege-account`, default on): the account is **not** SUPERADMIN and its explicit permissions are **exactly** `{MANAGE_PROJECTS}` | `403 ACCOUNT_TOO_PRIVILEGED` |
| 6 | Rate limit, **validate and import only** (default 10 requests/min per account, per server instance) | `429 RATE_LIMITED` |

Because of the order, an unauthenticated caller gets `401` and a caller without `MANAGE_PROJECTS` gets `403` even when the integration is disabled.

**Why check 5 exists:** in the existing permission model, an ADMIN with **no** permission rows has full CMS access. The integration rejects such accounts.

### 3.5 Restriction on existing APIs

`ClaudeIntegrationAccountRestrictionFilter` (default on, `restrict-integration-accounts`) applies to requests authenticated as an **allow-listed integration account**. Such an account may only call:

- `/api/v1/integrations/claude/**`
- `/api/v1/auth/{login|refresh|refresh-token|logout|session}` and the same under `/api/v1/admin-portal/auth/`
- `GET` (and `HEAD`/`OPTIONS`) `/api/v1/projects/admin/get/{slug}` and `/api/v1/projects/admin/get-by-id/{id}` (read-only verification)

Every other request returns:

```json
{"status":403,"error":"INTEGRATION_ACCOUNT_RESTRICTED","message":"The Claude integration account may only use the Claude integration API and read-only project checks."}
```

So the account's `MANAGE_PROJECTS` permission **cannot** be used to edit or delete projects or to use the admin Excel uploader. Tests confirm `DELETE /api/v1/projects/delete/{id}` and `POST /api/v1/excel-upload/projects` return `403` for it. Requests from any other account pass through unchanged.

---

## 4. API reference

Common header for all endpoints: `Authorization: Bearer <JWT_TOKEN>`. Upload endpoints use `Content-Type: multipart/form-data` (curl sets this when you use `-F`).

### 4.0 Error body (connector endpoints)

```json
{ "timestamp": "2026-11-23T07:00:00Z", "status": 400, "error": "EXCEL_EXTENSION", "message": "excelFile must be .xlsx or .xls" }
```

`409` and `422` use richer bodies, shown below. Stack traces and server paths are never returned.

### 4.1 `POST /api/v1/integrations/claude/properties/validate`: dry run

Checks the workbook and ZIP against the live database. **Writes nothing**: no job, project, builder or file. Temporary upload copies are deleted at the end of the request.

| Multipart field | Required | Rules |
|---|---|---|
| `excelFile` | yes | name ends `.xlsx`/`.xls` and content is a real workbook (magic bytes). ≤ 20 MB (`max-excel-bytes`). |
| `imagesZip` | no | name ends `.zip` and content starts with a ZIP signature. ≤ 300 MB (`max-zip-bytes`). |

**`200 OK`** → `ClaudeValidationReport`:

| Field | Meaning |
|---|---|
| `valid` | `true` only if there is no ERROR anywhere **and** no duplicate row **and** at least one row |
| `importableRows` | rows with status `VALID` (0 if the workbook or ZIP has batch-level errors) |
| `blockedRows`, `duplicateRows`, `totalRows` | row counts |
| `errorCount`, `warningCount` | total issues |
| `workbook` | `filename, format, sheetName, sheetNames[], headerRow, firstDataRow, lastDataRow, dataRows, detectedHeaders[], missingRequiredHeaders[], unknownHeaders[], unsupportedFields{}` |
| `workbook.detectedHeaders[]` | `column` (letter), `rawHeader`, `normalizedHeader`, `mappedTo`, `status`: `MAPPED`, `REFERENCE_ONLY`, `NEW_BUILDER_ONLY`, `FAQ`, `UNKNOWN`, `DUPLICATE` |
| `zip` | present only if a ZIP was sent: `filename, entries, images, extractedBytes, issues[]` |
| `workbookIssues[]` | sheet/header-level issues |
| `rows[]` | per row (below) |

`rows[]` fields: `excelRow, sourceReference` (the `ID` cell)`, projectName, builderName, cityName, reraNo, predictedSlug, status` (`VALID` | `BLOCKED` | `DUPLICATE_REVIEW_REQUIRED`)`, existingProjectId, duplicateReasons[], newBuilder, builderId, amenitiesMatched[], floorPlans, locationBenefits, faqs, images[]` (`file, type, slot, width, height, format`)`, errors[], warnings[], unappliedFields{}`.

Issue object (`ClaudeValidationIssue`), null fields omitted:

```json
{ "severity": "ERROR", "code": "UNKNOWN_AMENITY", "message": "Amenity \"Rainwater-Harvesting\" does not match any existing amenity (did you mean \"Rain Water Harvesting\"?). Amenities are never created or skipped silently.", "excelRow": 2, "column": "PROJECT AMENITIES", "value": "Rainwater-Harvesting" }
```

`severity`: `ERROR` blocks; `WARNING` and `INFO` don't. A row with both a duplicate and an error has status `BLOCKED`.

Example (abridged; shaped like the result for the reference workbook, which has 20 rows and 0 importable):

```json
{
  "valid": false, "importableRows": 0, "blockedRows": 20, "duplicateRows": 0, "totalRows": 20,
  "errorCount": 21, "warningCount": 80,
  "workbook": {
    "filename": "Property List-1 - Copy (1).xlsx", "format": "xlsx", "sheetName": "Sheet1", "sheetNames": ["Sheet1"],
    "headerRow": 1, "firstDataRow": 2, "lastDataRow": 21, "dataRows": 20,
    "detectedHeaders": [
      { "column": "A", "rawHeader": "ID", "normalizedHeader": "ID", "mappedTo": "Not stored on the project. …", "status": "REFERENCE_ONLY" },
      { "column": "F", "rawHeader": " PROJECT CONFIGURATION", "normalizedHeader": "PROJECT CONFIGURATION", "mappedTo": "projects.project_configuration + floor_plans rows …", "status": "MAPPED" }
    ],
    "missingRequiredHeaders": [], "unknownHeaders": [],
    "unsupportedFields": { "ID": "…", "BUILDER DESCRIPTION": "…", "LOCATION ADVANTAGE": "…" }
  },
  "workbookIssues": [],
  "rows": [
    {
      "excelRow": 5, "sourceReference": "4", "projectName": "Global City Walk", "builderName": "Garg Realtors",
      "cityName": "Ghaziabad", "reraNo": "UPRERAPRJ958302", "predictedSlug": "global-city-walk", "status": "BLOCKED",
      "duplicateReasons": [], "newBuilder": true, "amenitiesMatched": ["24 x 7 Water Supply", "…"],
      "floorPlans": 1, "locationBenefits": 0, "faqs": 4, "images": [],
      "errors": [ { "severity": "ERROR", "code": "UNKNOWN_PROJECT_STATUS", "excelRow": 5, "column": "PROJECT STATUS NAME", "value": "New Launch", "message": "…" } ],
      "warnings": [ { "severity": "WARNING", "code": "NEW_BUILDER", "…": "…" } ],
      "unappliedFields": { "LOCATION ADVANTAGE": "Global City Walk by Garg Realtors is located in …" }
    }
  ]
}
```

(Issue counts in the example are approximate. Row and status counts match the real result.)

Other responses: `400` (`EXCEL_REQUIRED`, `EXCEL_EXTENSION`, `ZIP_EXTENSION`, `ZIP_INVALID`, `MISSING_PARAMETER`), `401`, `403`, `404` (disabled), `413 FILE_TOO_LARGE`, `429`.

### 4.2 `POST /api/v1/integrations/claude/properties/import`: create drafts (asynchronous)

| Multipart field / param | Required | Default | Notes |
|---|---|---|---|
| `excelFile` | yes | | same rules as validate |
| `imagesZip` | no | | same rules as validate |
| `batchName` | no | | label, stored truncated to 200 chars |
| `dryRun` | no | `false` | `true` = validate only, returns `200`, writes nothing |
| `allowPartial` | no | `false` | `false`: any `BLOCKED` or `DUPLICATE_REVIEW_REQUIRED` row refuses the whole batch. `true`: valid rows import, others are recorded. |
| `idempotencyKey` | no | SHA-256 of the files | your own batch key, scoped to your account |

Processing on submit: files are copied to private temp storage → idempotency check → job `RECEIVED` → `VALIDATING` → refuse (`422`) or queue (`202`). A single background thread (`claudeImportExecutor`, queue capacity 20) processes jobs one at a time. At processing time the files are **re-validated against the current database**.

| Status | When | Body (`ClaudeImportResponse`, nulls omitted) |
|---|---|---|
| `202 Accepted` | queued | `{"jobId","status":"QUEUED","dryRun":false,"message","statusUrl","resultsUrl","validation":{…}}` |
| `200 OK` | `dryRun=true` | `{"dryRun":true,"message":"Dry run passed; nothing was written." \| "Dry run found problems; nothing was written.","validation":{…}}` |
| `422 Unprocessable Entity` | workbook/ZIP errors, **or** no importable row, **or** blocked/duplicate rows without `allowPartial` | `{"jobId","status":"VALIDATION_FAILED","dryRun":false,"message","statusUrl","resultsUrl","validation":{…}}`. A job record is kept (rows `SKIPPED_VALIDATION` / `DUPLICATE_REVIEW_REQUIRED`), and its idempotency key is released so fixed files can be resubmitted. |
| `409 Conflict` | same files (or same `idempotencyKey`) already accepted | `{"error":"DUPLICATE_BATCH","message":"This batch was already submitted. Poll the existing job instead of resubmitting.","jobId","status","statusUrl"}` |
| `400`, `401`, `403`, `404`, `413`, `429` | as in validate | error body |
| `503 Service Unavailable` | import queue full | `{"error":"QUEUE_FULL",…}` |
| `500` | unexpected | `{"error":"SUBMISSION_FAILED" \| "INTERNAL_ERROR",…}` |

`202` example:

```json
{
  "jobId": "4f1c2a9e-0b7d-4c55-9a51-2f0b0f6f3d10",
  "status": "QUEUED",
  "dryRun": false,
  "message": "2 row(s) queued for draft creation. Poll statusUrl, then read resultsUrl.",
  "statusUrl": "/api/v1/integrations/claude/jobs/4f1c2a9e-0b7d-4c55-9a51-2f0b0f6f3d10",
  "resultsUrl": "/api/v1/integrations/claude/jobs/4f1c2a9e-0b7d-4c55-9a51-2f0b0f6f3d10/results",
  "validation": { "valid": true, "importableRows": 2, "...": "…" }
}
```

`422` messages (exact text): `"The workbook or ZIP has blocking errors; nothing was imported."`, `"No row can be imported; nothing was imported."`, `"Some rows have blocking errors or are duplicates; nothing was imported. Fix them, or resubmit with allowPartial=true."`

### 4.3 `GET /api/v1/integrations/claude/jobs/{jobId}`

`200` → `ClaudeJobResponse` (null fields omitted):

```json
{
  "jobId": "4f1c2a9e-…", "batchName": "November_Property_Batch", "status": "PARTIALLY_COMPLETED", "allowPartial": true,
  "totalProperties": 4, "successfulProperties": 1, "failedProperties": 0, "duplicateProperties": 3,
  "skippedProperties": 0, "pendingProperties": 0,
  "originalExcelFilename": "properties.xlsx", "originalZipFilename": "properties_images.zip",
  "createdAt": "2026-11-23T12:18:40", "startedAt": "2026-11-23T12:18:40", "completedAt": "2026-11-23T12:18:41",
  "errorSummary": "Created drafts: 1, duplicates: 3, skipped (validation): 0, failed: 0",
  "statusUrl": "/api/v1/integrations/claude/jobs/4f1c2a9e-…",
  "resultsUrl": "/api/v1/integrations/claude/jobs/4f1c2a9e-…/results"
}
```

`status` values: `RECEIVED`, `VALIDATING`, `VALIDATION_FAILED`, `QUEUED`, `PROCESSING`, `PARTIALLY_COMPLETED`, `COMPLETED`, `FAILED`. **Terminal:** `VALIDATION_FAILED`, `PARTIALLY_COMPLETED`, `COMPLETED`, `FAILED`.

| Final status | Rule (from `ClaudeJobService.finish`) |
|---|---|
| `COMPLETED` | **every** row is `CREATED_DRAFT`, `verified=true`, `draft=true` and has no errors |
| `PARTIALLY_COMPLETED` | at least one draft created, but not all rows are clean |
| `FAILED` | no draft created (the idempotency key is released so the batch can be retried) |

Unknown id → `404 {"error":"JOB_NOT_FOUND","message":"No job with id …"}`.

### 4.4 `GET /api/v1/integrations/claude/jobs/{jobId}/results`

`200` → `{"job": <ClaudeJobResponse>, "results": [<ClaudePropertyResult>, …]}`, results ordered by Excel row.

```json
{
  "excelRow": 2, "sourceReference": "1",
  "projectName": "Example Heights", "builderName": "Example Developers", "cityName": "Ghaziabad", "reraNo": "UPRERAPRJ000000",
  "projectId": 1402, "slug": "example-heights",
  "draft": true, "verified": true,
  "builderId": 512, "builderCreated": true,
  "imagesExpected": 5, "imagesImported": 5,
  "importStatus": "CREATED_DRAFT",
  "errors": [], "warnings": [ { "severity": "WARNING", "code": "NEW_BUILDER", "…": "…" } ],
  "unappliedFields": {}
}
```

| Field | Meaning |
|---|---|
| `projectId` | new draft id (only for `CREATED_DRAFT`) |
| `existingProjectId` | matched project for duplicates |
| `slug` | predicted slug, or the created project's slug |
| `draft` | read back from the database: `true` = `projects.status` is false |
| `verified` | the database read-back matched name, slug, builder, city, status, type, RERA, FAQ/floor-plan/location-benefit/amenity counts, and all expected images were attached |
| `builderCreated` | the import created this builder |
| `unappliedFields` | populated values that were **not** stored (e.g. `BUILDER DESCRIPTION` for an existing builder, unparseable `LOCATION ADVANTAGE`) |

`importStatus` values (enum `ClaudeItemStatus`): `PENDING`, `CREATED_DRAFT`, `DUPLICATE_REVIEW_REQUIRED`, `SKIPPED_VALIDATION`, `FAILED`. See [§7 Step 9](#step-9--fetch-property-level-results).

### 4.5 `GET /api/v1/integrations/claude/template`

| Param | Default | Result |
|---|---|---|
| `format=xlsx` | yes | download `mpf-claude-property-template.xlsx`: sheet `Sheet1` (32 headers, trimmed), `Column guide`, `Image names` |
| `format=json` | | JSON: `sheetName, headerRow, firstDataRow, headerMatching, columns[{column, header, required, maxLength, target}], optionalExtraColumns[], images{fileName, projectKey, folders, types[{slot, types, storedSize, maxPerProject}], example}` |

Not rate limited.

---

## 5. Excel workbook format

Reference workbook: `Property List-1 - Copy (1).xlsx` (verified: sheet `Sheet1`, header row 1, data rows 2–21 = 20 properties, 32 headers A–AF, column AG empty). A copy is the test fixture `src/test/resources/claude/reference-property-list.xlsx`.

### 5.1 Structure rules

- **Sheet:** the **first sheet** is read. If it isn't named `Sheet1` that's an INFO message; extra sheets produce a WARNING and are ignored.
- **Row 1 = headers. One property per row from row 2.** Fully empty rows are skipped. Max 500 data rows (`max-rows`).
- **Header matching:** trimmed, upper-cased, inner whitespace collapsed. So ` PROJECT CONFIGURATION` (leading space in the reference file) = `PROJECT CONFIGURATION`, and `PROJECT Amenities` = `PROJECT AMENITIES`. **Column order does not matter.**
- **Unknown header with values → batch blocked** (`EXCEL_UNKNOWN_HEADER`). An unknown header with no values → warning. A value in a column with **no header** → blocked (`EXCEL_VALUE_WITHOUT_HEADER`). A duplicate header → blocked.
- **Missing required header → batch blocked.**
- **Cell conversion** (same as the admin importer): text as-is (trimmed); whole numbers lose `.0` (`8920024793`); decimals keep it (`0.75`); formulas use their cached result.

### 5.2 Columns

Required = value must be non-blank in every row. Max = database column length (VARCHAR 255); longer values block the row (`VALUE_TOO_LONG`).

| Col | Header | Req. | Max | Stored in / behaviour |
|---|---|---|---|---|
| A | `ID` | | 100 | **Not stored on the project.** Saved as `sourceReference` in the job result. Never used to find or update a project. |
| B | `BUILDER NAME` | ✔ | 255 | Existing builder (name match, case/space-insensitive; else slug match with a warning) → `projects.builder_id`. Otherwise a **new builder** is created (warning `NEW_BUILDER`). |
| C | `PROJECT NAME` | ✔ | 255 | `projects.project_name`; `projects.slugurl = generateSlug(name)` |
| D | `PROPERTY TYPE NAME` | ✔ | 255 | must match `project_types` → `projects.property_type` |
| E | `PROJECT STATUS NAME` | ✔ | 255 | must match `project_status` → `projects.project_status_id` |
| F | `PROJECT CONFIGURATION` | | 255 | `projects.project_configuration`, plus one `floor_plans` row per comma-separated `<type>-<area> sq.ft` segment (sq.m computed) |
| G | `PROJECT LOCALITY` | | 255 | `projects.project_locality` |
| H | `CITY NAME` | ✔ | 255 | must match `cities` and belong to STATE NAME → `projects.city_Id` |
| I | `STATE NAME` | ✔ | 255 | must match `state` and belong to COUNTRY NAME → `projects.state_id` |
| J | `COUNTRY NAME` | ✔ | 255 | must match `country` (validation only) |
| K | `RERA NO` | | 255 | `projects.rera_no`; duplicate-detection key. Blank → warning `RERA_MISSING`. |
| L | `RERA WEBSITE` | | 255 | `projects.rera_website`; non-http(s) value → warning |
| M | `IVR NO` | | 255 | `projects.ivr_no`; non-phone value → warning |
| N | `PROJECT PRICE` | | 255 | `projects.project_price` as **text**. A bare number such as `0.75` is stored exactly as written (INFO); the unit is not recorded. |
| O | `META TITLE` | | 255 | `projects.meta_title` |
| P | `META DESCRIPTION` | | – | `projects.meta_description` |
| Q | `META KEYWORD` | | – | `projects.meta_keyword` |
| R | `AMENITY DESCRIPTION` | | – | `projects.amenity_desc` |
| S | `LOCATION DESCRIPTION` | | – | `projects.location_desc` |
| T | `FLOOR PLAN DESCRIPTION` | | – | `projects.floor_plan_desc` |
| U | `BUILDER DESCRIPTION` | | – | **Only for a builder created by this import** → `builders.builder_desc`. For an existing builder it is **never written** (warning `BUILDER_DESCRIPTION_NOT_APPLIED`, value kept in `unappliedFields`). If several rows create the same new builder, only the first row's description is used. |
| V | `PROJECT Amenities` | | – | links to existing `amenity` rows (`project_amenities`). Separators `_`, `\|`, `,`; `-` is read as a space. **Every token must match** an existing amenity, or the row is blocked (`UNKNOWN_AMENITY`, with a "did you mean" hint). |
| W | `LOCATION ADVANTAGE` | | – | `location_benefits` rows from segments `Place-Name-4.2-Km` or `Place Name (4.2 Km)` (separators `_`, `\|`, `,`). Unparseable text → warning `LOCATION_ADVANTAGE_UNPARSED`, kept in `unappliedFields`, **not stored**. Becomes blocking if `strict-location-advantage=true`. |
| X | `PROJECT WALKTHROUGH DESCRIPTION` | | – | `project_walkthrough.walkthrough_desc` |
| Y–AF | `Question 1` / `Answer 1` … `Question 4` / `Answer 4` | | – | one `project_faqs` row per complete pair. Pairs 1–20 are supported. Question without answer (or vice versa) blocks the row (`FAQ_INCOMPLETE`). A header without its partner header blocks the batch. |
| – | `PROJECT ABOUT SHORT DESCRIPTION` (optional, not in reference file) | | – | `projects_about.long_desc` |

Also set on every new project: `status=false`, `show_featured_properties=false`, `created_by_user_id` = integration account, and `state_id` from the resolved state.

### 5.3 Master data (must already exist; nothing is auto-created, no fallback)

Lookup is case-insensitive with whitespace collapsed; for amenities hyphens and underscores also count as spaces. **Spelling must match.**

Values seen in the local dev database on 2026-11-23 (your environment may differ: **verify with `/properties/validate`**):

- **Project status:** `Completed`, `Ready To Move`, `New Launched`, `Under Construction`, `Ultra Luxury`, `Featured` (`New Launch` is **not** valid)
- **Property type:** `Residential`, `Commercial`, `New Launches`
- **Country:** `India`
- **Cities used by the reference file:** Ghaziabad, Noida, Greater Noida, `Noida extension` (all Uttar Pradesh), Gurugram (Haryana)
- **Amenities:** 138 titles, e.g. `24 x 7 Water Supply`, `24X7 Security`, `Cctv Camera Security`, `Ev Charger`, `Wi-fi Connectivity`, `Rain Water Harvesting`, `Children's Play Area`, `Swimming Pool`, `Gym`. Write them in the cell as `24-x-7-Water-Supply_Rain-Water-Harvesting_Wi-Fi-Connectivity`.

Ambiguous matches (two master rows with the same normalised name) block the row (`AMBIGUOUS_*`).

### 5.4 Sample row (placeholder data, not a real project)

| Header | Value |
|---|---|
| ID | `1` |
| BUILDER NAME | `Example Developers` |
| PROJECT NAME | `Example Heights` |
| PROPERTY TYPE NAME | `Residential` |
| PROJECT STATUS NAME | `Under Construction` |
| PROJECT CONFIGURATION | `2 BHK-1150 sq.ft, 3 BHK-1500 sq.ft` |
| PROJECT LOCALITY | `Sector 00` |
| CITY NAME | `Ghaziabad` |
| STATE NAME | `Uttar Pradesh` |
| COUNTRY NAME | `India` |
| RERA NO | *(the verified number, or blank)* |
| RERA WEBSITE | `https://www.up-rera.in/` |
| IVR NO | `<company IVR number>` |
| PROJECT PRICE | *(verified value with unit, e.g. `1.2 Cr`, or blank)* |
| META TITLE | `Example Heights Ghaziabad \| 2 & 3 BHK Apartments` |
| META DESCRIPTION | `Explore Example Heights in Sector 00, Ghaziabad: 2 and 3 BHK apartments…` |
| META KEYWORD | `Example Heights, Example Heights Ghaziabad, 2 BHK flats in Ghaziabad` |
| AMENITY DESCRIPTION / LOCATION DESCRIPTION / FLOOR PLAN DESCRIPTION | factual paragraphs about **this** project only |
| BUILDER DESCRIPTION | `Example Developers is …` (stored only if the builder is new) |
| PROJECT Amenities | `Swimming-Pool_Gym_Power-Backup_Rain-Water-Harvesting` |
| LOCATION ADVANTAGE | `Example-Metro-Station-1.2-Km_Example-Hospital-3.5-Km` |
| PROJECT WALKTHROUGH DESCRIPTION | short walkthrough paragraph |
| Question 1 … Answer 4 | four complete Q&A pairs |

---

## 6. Images ZIP format

### 6.1 File names

```
<Project-Key>_<Type>[_<anything>].<jpg|jpeg|png|webp>
```

- The base name is split on `_`. **Part 1 = project key, part 2 = type.** Anything after part 2 is ignored, so the conventional `_My-Property-Fact` suffix is optional. A name with fewer than two parts is rejected (`IMAGE_BAD_NAME`).
- **Project key = PROJECT NAME with spaces replaced by `-`.** Matching is by slug: `generateSlug(key with "-" → " ")` must equal the row's slug. It is **not** a database id and **not** the `ID` column. `Victory-Ace` → row "Victory Ace". `Victory-Ace-7` → would need a row named "Victory Ace 7".
- Don't put `_` in the key (it splits the name). Punctuation is dropped by the slug, so "Children's Park" → `Childrens-Park`.
- Extensions are case-insensitive. The extension must match the actual content (`IMAGE_EXTENSION_MISMATCH`). **GIF and BMP are not accepted.**

### 6.2 Types

Accepted type token (case-insensitive) = base word + optional number (`-1`, `1`, up to 3 digits).

| Base word | Slot | Stored | Max per project |
|---|---|---|---|
| `Logo` | `projects.project_logo` | fit within **792 × 203** | 1 |
| `Thumbnail` | `projects.project_thumbnail` | fit within **800 × 800** | 1 |
| `Location` / `LocationMap` | `projects.location_map` | fit within **1200 × 800** | 1 |
| `Desktop` / `DesktopBanner` | `project_desktop_banners` | fit within **2508 × 1200** | many |
| `Mobile` / `MobileBanner` | `project_mobile_banners` | fit within **800 × 800** | many |
| `Gallery` | `project_gallery` (`type=gallery`) | fit within **1600 × 1200** | many |

- **Any other token is rejected** (`IMAGE_UNKNOWN_TYPE`, with a hint). Examples: `ProjectLogo`, `ProjectThumbnail`, `Opening-1`, `Brochure`. The admin uploader would silently save these as gallery images; the integration refuses them.
- **Each type token must be unique per project** (`IMAGE_DUPLICATE_TYPE`). Number repeating types: `Gallery-1`, `Gallery-2`, `Desktop-1`, `Desktop-2`. Two files that both have type `Gallery` (e.g. `X_Gallery_1.jpg` and `X_Gallery_2.jpg`) are **rejected**, because the type token is `Gallery` in both. The trailing `_1`/`_2` is ignored.
- Core images Desktop, Mobile and Thumbnail: if any is missing → warning `IMAGE_CORE_MISSING` (blocking if `require-core-images=true`). With no ZIP at all, every row gets warning `NO_IMAGES`.

### 6.3 Correct and incorrect names

| File name | Result |
|---|---|
| `Example-Heights_Desktop-1_My-Property-Fact.jpg` | ✔ desktop banner |
| `Example-Heights_Desktop-1.jpg` | ✔ desktop banner (suffix optional) |
| `Example-Heights_Mobile-1_My-Property-Fact.jpg` | ✔ mobile banner |
| `Example-Heights_Thumbnail_My-Property-Fact.jpg` | ✔ thumbnail |
| `Example-Heights_Thumbnail_1.jpg` | ✔ thumbnail (`_1` ignored) |
| `Example-Heights_Location_My-Property-Fact.jpg` / `…_LocationMap_….png` | ✔ location map |
| `Example-Heights_Logo_My-Property-Fact.png` / `Example-Heights_Logo_1.png` | ✔ logo |
| `Example-Heights_Gallery-1_My-Property-Fact.jpg`, `…_Gallery-2_….jpg` | ✔ two gallery images |
| `Example-Heights_ProjectLogo_My-Property-Fact.png` | ✘ `IMAGE_UNKNOWN_TYPE` (use `Logo`) |
| `Example-Heights_Gallery_1.jpg` + `Example-Heights_Gallery_2.jpg` | ✘ `IMAGE_DUPLICATE_TYPE` (use `Gallery-1`, `Gallery-2`) |
| `Example-Heights_Logo.png` + `Example-Heights_Logo-2.png` | ✘ `IMAGE_MULTIPLE_LOGO` |
| `ExampleHeights.jpg` | ✘ `IMAGE_BAD_NAME` (no type part) |
| `Other-Project_Gallery-1.jpg` (no such row in the workbook) | ✘ `IMAGE_PROJECT_NOT_IN_WORKBOOK` (blocks the batch) |
| `Example-Heights_Gallery-1.gif` | ✘ `ZIP_UNEXPECTED_FILE` |
| `Example-Heights_Gallery-1.jpg` that is really a PNG | ✘ `IMAGE_EXTENSION_MISMATCH` |
| `1402_Gallery-1.jpg` (database id as key) | ✘ ids are not keys; it would look for a project named "1402" |

### 6.4 ZIP layout

```
properties_images.zip
├── Example-Heights/
│   ├── Example-Heights_Desktop-1_My-Property-Fact.jpg
│   ├── Example-Heights_Mobile-1_My-Property-Fact.jpg
│   ├── Example-Heights_Thumbnail_My-Property-Fact.jpg
│   ├── Example-Heights_Location_My-Property-Fact.jpg
│   ├── Example-Heights_Logo_My-Property-Fact.png
│   ├── Example-Heights_Gallery-1_My-Property-Fact.jpg
│   └── Example-Heights_Gallery-2_My-Property-Fact.jpg
└── Example-Gardens/
    ├── Example-Gardens_Desktop-1_My-Property-Fact.jpg
    ├── Example-Gardens_Mobile-1_My-Property-Fact.jpg
    └── Example-Gardens_Thumbnail_My-Property-Fact.jpg
```

- **Folders are optional.** Files may sit at the ZIP root. Nested folders are allowed, but the **innermost folder name** must be the same project key (`IMAGE_FOLDER_MISMATCH` otherwise).
- **File names must be unique across the whole ZIP**, case-insensitive (`ZIP_DUPLICATE_FILE_NAME`), even in different folders.
- `__MACOSX/…`, `._*`, `.DS_Store`, `Thumbs.db` and `desktop.ini` are ignored (INFO). Any other non-image file → blocked (`ZIP_UNEXPECTED_FILE`).

### 6.5 Limits and validation (defaults, configurable, see §9)

| Check | Default | Code |
|---|---|---|
| ZIP upload size | 300 MB | `FILE_TOO_LARGE` (413) |
| Total extracted bytes (counted while reading) | 1 GB | `ZIP_EXTRACTED_TOO_LARGE` |
| Entries | 2000 | `ZIP_TOO_MANY_ENTRIES` |
| Single image | 40 MB | `IMAGE_TOO_LARGE` |
| Compression ratio per entry | 100 : 1 | `ZIP_SUSPICIOUS_RATIO` |
| Pixels | 60 megapixels | `IMAGE_TOO_MANY_PIXELS` |
| Minimum side | 100 px | `IMAGE_TOO_SMALL` |
| Path traversal / absolute paths (`..`, `/x`, `C:`) | rejected | `ZIP_UNSAFE_PATH` |
| Content | JPEG/PNG/WEBP magic bytes, valid end marker (JPEG `FFD9`, PNG `IEND`, WEBP RIFF size), full decode | `IMAGE_INVALID_CONTENT`, `IMAGE_CORRUPTED` |
| Unreadable/encrypted archive | rejected | `ZIP_UNREADABLE` |

A file-level problem whose file name identifies a row is attached to **that row** (row blocked). Otherwise it is a ZIP-level issue (**batch blocked**).

### 6.6 What happens to images on import

- Images are processed only for rows created as drafts in this job.
- **Every image is resized to fit its box (aspect ratio kept, not cropped; smaller images are upscaled and sharpened) and re-encoded** at quality 0.98, using the same `FileUtils.saveImageFromBytes` routine as the admin uploader. Original dimensions are **not** preserved. The validator warns `IMAGE_WILL_BE_UPSCALED` (smaller than the box) and `IMAGE_ASPECT_RATIO` (more than 5% off the box ratio).
- WEBP input: if no WEBP writer is available at runtime, the existing routine saves as JPEG. Whether a WEBP writer is present in your deployment: **Needs verification** (expect `.jpg`).
- Files are stored at `${upload_dir}properties/<slug>/<timestamp>_<type>_<8-char-uuid>.<ext>`. Alt tag: `"<Project Name> - <type with '-' as space>"` (e.g. `Example Heights - Gallery 2`). Existing files are never overwritten (names are unique).
- **If an image fails:** if one file can't be processed, the others continue; the row is still `CREATED_DRAFT` with a `POST_IMPORT_CHECK` error and `imagesImported < imagesExpected`, so the job ends `PARTIALLY_COMPLETED`. If the project's image records can't be saved, the files written for that project in this call are deleted. No other file is ever deleted.

---

## 7. End-to-end workflow for Claude

### Step 1: Property research

Collect, per property: project name, builder, property type, status, configuration (unit types and areas), locality, city, state, country, RERA number and verification URL, price, amenities, location advantages with distances, descriptions, floor-plan details, builder description, FAQs, SEO title/description/keywords.

Source priority (record the source URL and check date for every fact):

1. Official state RERA portal
2. Official builder or project website
3. Official brochure
4. Reputable third-party portals, **for cross-checking only**

Classify each value as `VERIFIED_OFFICIAL`, `OFFICIAL_UNVERIFIED`, `THIRD_PARTY`, `AI_GENERATED` (prose, FAQs) or `UNKNOWN`. **Never invent** RERA numbers, prices, possession dates, configurations, amenities, approvals or distances. Unknown → **leave the cell blank**. Keep provenance in a separate research report (e.g. `research/<batch>/report.json`), **not** in extra Excel columns (unknown columns block the batch).

### Step 2: RERA verification

- Look up the project or promoter on the official portal of its state (for example UP-RERA, HARERA). Record the exact number, registration date, promoter and URL.
- Not found or portal unavailable → leave `RERA NO` blank or mark it unverified in the research report. Never present a portal-sourced number as verified.
- Expect township-wide numbers. If validation reports a RERA duplicate, stop for that property and hand it to a human.

### Step 3: Excel preparation

- Start from `GET /template` (xlsx) or the reference workbook. **Do not rename headers or change their meaning.** No extra columns. One row per property.
- `ID` = serial number only (1, 2, 3…). **Never put database project ids anywhere.**
- Use exact master-data values (§5.3). Format list columns as in §5.2 (`Swimming-Pool_Gym`, `Place-Name-1.2-Km`, `2 BHK-1150 sq.ft`).
- Every FAQ pair complete. Texts must describe **only that project**; the validator flags `DUPLICATE_TEXT_ACROSS_ROWS` and `TEXT_MENTIONS_OTHER_PROJECT`.

### Step 4: Image preparation

- Allowed: official media with permission, licensed images, or AI-generated **illustrations** (never presented as real photographs). **Not allowed:** images copied from property portals.
- If rights cannot be established, send the property without images or leave it out.
- Prepare at least `Desktop-1` (≥ 2508×1200), `Mobile-1` (≥ 800×800 square) and `Thumbnail` (≥ 800×800 square). Optional: `Logo` (≥ 792×203, PNG), `Location` (≥ 1200×800), `Gallery-N` (≥ 1600×1200).
- Name and package them per §6. Pre-check: every row has its files, every key matches a row, file names are unique, only jpg/png/webp, ZIP ≤ 300 MB (split bigger batches).

### Step 5: API authentication

Read `INTEGRATION_EMAIL`, `INTEGRATION_PASSWORD` and `INTEGRATION_DASHBOARD_USERNAME` from the environment. Call the login endpoint (§3.2) and keep `token` in memory only. On `401`, log in again **once**.

### Step 6: Validate before importing

`POST /properties/validate` with both files. Then:

| Finding | Action |
|---|---|
| Any `ERROR` in `workbookIssues` or `zip.issues` | Stop; fix the file; validate again |
| Row `BLOCKED` | Fix the cause from sources (never by guessing) or remove the row |
| `UNKNOWN_AMENITY` / `UNKNOWN_CITY` / `UNKNOWN_PROJECT_STATUS` / … | Use the exact master value (see the message and hint). If the value is genuinely missing, a human must add it in the admin panel. |
| `REQUIRED_VALUE_MISSING`, `EXCEL_MISSING_REQUIRED_HEADER` | Add the data or header |
| Row `DUPLICATE_REVIEW_REQUIRED` | Remove the row. Report `existingProjectId` and `duplicateReasons`. **Never rename a project to get past the check.** |
| `IMAGE_*` errors | Rename, replace or remove the files |
| Warnings | Read every one. Fix or explain `BUILDER_DESCRIPTION_NOT_APPLIED`, `LOCATION_ADVANTAGE_UNPARSED`, `IMAGE_CORE_MISSING`, `TEXT_MENTIONS_OTHER_PROJECT` in the final report |

**Never import blocked rows.** Continue when `valid: true`. Or, deliberately, use `allowPartial=true` to import only `VALID` rows, and list the others in the report. Limit yourself to 3 fix-and-validate rounds without human input.

### Step 7: Import

`POST /properties/import` with the **same** files, `batchName`, optionally `idempotencyKey` (your batch id), and `allowPartial` only if decided in step 6.

- `202` → save `jobId`. Processing is asynchronous.
- `409 DUPLICATE_BATCH` → **do not resubmit**; poll the returned `jobId`.
- `422` → read `validation` and go back to step 6.
- Rows created earlier become duplicates if resubmitted. Only resubmit rows that were **not** created, in a new workbook.

### Step 8: Monitor the job

Poll `GET /jobs/{jobId}` every 5–10 seconds until `status` is terminal (`COMPLETED`, `PARTIALLY_COMPLETED`, `FAILED`, `VALIDATION_FAILED`). Stop after about 30 minutes and report the job as stuck (**the timeout is a workflow recommendation**, not a server setting).

### Step 9: Fetch property-level results

`GET /jobs/{jobId}/results`:

| `importStatus` | Meaning | What to do |
|---|---|---|
| `CREATED_DRAFT` | New unpublished draft. Success **only if** `draft=true`, `verified=true`, `errors=[]` and `imagesImported == imagesExpected`. | Report `projectId` and `slug`. If there are `POST_IMPORT_CHECK` errors, report the draft as created but needing review. |
| `DUPLICATE_REVIEW_REQUIRED` | Matched an existing project or an earlier Claude import; nothing written | Report `existingProjectId` and reasons for human review |
| `SKIPPED_VALIDATION` | Row had blocking errors (or the batch was refused, `BATCH_REFUSED`); nothing written | Report the errors |
| `FAILED` | Row transaction rolled back (`ROW_FAILED`), or the job ended first (`JOB_ABORTED`); nothing from that row remains | Report; it can be fixed and resubmitted |
| `PENDING` | Not processed yet | Keep polling |

### Step 10: Final verification and report

Optional cross-check: `GET /api/v1/projects/admin/get-by-id/{projectId}` (allowed for the integration account) returns the draft. The public `GET /api/v1/projects/get/{slug}` must **not** return it (it only serves published projects).

Report template:

```markdown
# Batch <batchName>, job <jobId>: <final status>
Submitted: N · Drafts created: N · Duplicates: N · Skipped: N · Failed: N · Images attached: X/Y

## Created drafts (verified)
| Row | Project | Project ID | Slug | Images | Builder (new/existing) |

## Needs review
| Row | Project | Status | Reason | Existing project ID |

## Not stored (unappliedFields) and warnings
- Row 3, BUILDER DESCRIPTION: existing builder; text kept for a human.

## Sources
research/<batchName>/report.json
```

**Never claim success from the initial `202`.** Only the finished job and per-row results count.

---

## 8. Copy-paste API examples

```bash
# --- configuration (placeholders; never hard-code secrets) ---
export BASE_URL="http://localhost:8005"          # dev profile port; staging: https://<staging-api-host>
export INTEGRATION_EMAIL="<integration account email>"
export INTEGRATION_PASSWORD="<from your secret store>"
export INTEGRATION_DASHBOARD_USERNAME="<dashboard username>"
export EXCEL_FILE="./properties.xlsx"
export IMAGES_ZIP="./properties_images.zip"
API="$BASE_URL/api/v1/integrations/claude"
```

**1–2. Log in and keep the JWT**

```bash
JWT_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/admin-portal/auth/login" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg e "$INTEGRATION_EMAIL" --arg p "$INTEGRATION_PASSWORD" --arg d "$INTEGRATION_DASHBOARD_USERNAME" \
        '{email:$e, password:$p, dashboardUsername:$d}')" | jq -r '.token')
[ -n "$JWT_TOKEN" ] && [ "$JWT_TOKEN" != "null" ] || { echo "login failed"; exit 1; }
AUTH="Authorization: Bearer $JWT_TOKEN"
```

**3. Template**

```bash
curl -s -H "$AUTH" "$API/template?format=json" | jq '.columns[] | {column, header, required}'
curl -s -H "$AUTH" -o mpf-claude-property-template.xlsx "$API/template"
```

**4. Validate (writes nothing)**

```bash
curl -s -H "$AUTH" -F "excelFile=@$EXCEL_FILE" -F "imagesZip=@$IMAGES_ZIP" "$API/properties/validate" \
  | tee validation.json | jq '{valid, importableRows, blockedRows, duplicateRows, errorCount, warningCount}'
jq '.rows[] | select(.status != "VALID") | {excelRow, projectName, status, errors: [.errors[].code], duplicateReasons}' validation.json
```

**5. Import**

```bash
RESP=$(curl -s -w '\n%{http_code}' -H "$AUTH" \
  -F "excelFile=@$EXCEL_FILE" -F "imagesZip=@$IMAGES_ZIP" \
  --form-string "batchName=November_Property_Batch" \
  --form-string "allowPartial=false" \
  "$API/properties/import")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
echo "HTTP $HTTP"; echo "$BODY" | jq '{jobId, status, message}'
JOB_ID=$(echo "$BODY" | jq -r '.jobId')
```

(`202` = queued, `409` = already submitted (use the returned `jobId`), `422` = refused (see `.validation`).)

**6. Poll status until terminal**

```bash
while :; do
  STATUS=$(curl -s -H "$AUTH" "$API/jobs/$JOB_ID" | jq -r '.status')
  echo "status: $STATUS"
  case "$STATUS" in COMPLETED|PARTIALLY_COMPLETED|FAILED|VALIDATION_FAILED) break;; esac
  sleep 5
done
```

**7. Results**

```bash
curl -s -H "$AUTH" "$API/jobs/$JOB_ID/results" | tee results.json \
  | jq '.results[] | {excelRow, projectName, importStatus, projectId, slug, draft, verified, imagesImported, imagesExpected, errors: [.errors[].code]}'
jq '.job | {status, successfulProperties, duplicateProperties, skippedProperties, failedProperties}' results.json
```

**Dry run through the import endpoint** (same as validate): add `--form-string "dryRun=true"`.

---

## 9. Environment configuration

### 9.1 Integration settings (`@ConfigurationProperties(prefix = "claude.integration")`)

All have safe defaults in code; set them as **environment variables** (Spring relaxed binding: upper-case, `.` → `_`, dashes removed). No properties file needs editing.

| Property | Environment variable | Default |
|---|---|---|
| `enabled` | `CLAUDE_INTEGRATION_ENABLED` | `false` |
| `allowed-emails` | `CLAUDE_INTEGRATION_ALLOWEDEMAILS` (comma-separated) | *(empty = nobody)* |
| `require-least-privilege-account` | `CLAUDE_INTEGRATION_REQUIRELEASTPRIVILEGEACCOUNT` | `true` |
| `restrict-integration-accounts` | `CLAUDE_INTEGRATION_RESTRICTINTEGRATIONACCOUNTS` | `true` |
| `rate-limit-per-minute` | `CLAUDE_INTEGRATION_RATELIMITPERMINUTE` | `10` (`0` disables) |
| `temp-dir` | `CLAUDE_INTEGRATION_TEMPDIR` | `${java.io.tmpdir}/mpf-claude` |
| `max-excel-bytes` | `CLAUDE_INTEGRATION_MAXEXCELBYTES` | `20971520` (20 MB) |
| `max-zip-bytes` | `CLAUDE_INTEGRATION_MAXZIPBYTES` | `314572800` (300 MB) |
| `max-extracted-bytes` | `CLAUDE_INTEGRATION_MAXEXTRACTEDBYTES` | `1073741824` (1 GB) |
| `max-zip-entries` | `CLAUDE_INTEGRATION_MAXZIPENTRIES` | `2000` |
| `max-image-bytes` | `CLAUDE_INTEGRATION_MAXIMAGEBYTES` | `41943040` (40 MB) |
| `max-compression-ratio` | `CLAUDE_INTEGRATION_MAXCOMPRESSIONRATIO` | `100` |
| `max-image-pixels` | `CLAUDE_INTEGRATION_MAXIMAGEPIXELS` | `60000000` |
| `min-image-side` | `CLAUDE_INTEGRATION_MINIMAGESIDE` | `100` |
| `max-rows` | `CLAUDE_INTEGRATION_MAXROWS` | `500` |
| `strict-location-advantage` | `CLAUDE_INTEGRATION_STRICTLOCATIONADVANTAGE` | `false` |
| `require-core-images` | `CLAUDE_INTEGRATION_REQUIRECOREIMAGES` | `false` |

The property names are verified in code and tests. The environment-variable spellings follow Spring Boot's documented relaxed-binding rules; tests set the properties directly.

### 9.2 `.env.example` (no real values)

```dotenv
# --- backend (MPF API process) ---
CLAUDE_INTEGRATION_ENABLED=true
CLAUDE_INTEGRATION_ALLOWEDEMAILS=claude-importer@example.com
CLAUDE_INTEGRATION_TEMPDIR=/var/mpf/claude-tmp
# Existing Spring settings, if you override them via environment:
# SPRING_DATASOURCE_URL=jdbc:mysql://<host>:3306/<database>
# SPRING_DATASOURCE_USERNAME=<db user>
# SPRING_DATASOURCE_PASSWORD=<db password>

# --- agent side (never committed, never logged) ---
BASE_URL=http://localhost:8005
INTEGRATION_EMAIL=claude-importer@example.com
INTEGRATION_PASSWORD=<set in secret store>
INTEGRATION_DASHBOARD_USERNAME=<dashboard username>
```

### 9.3 Integration account setup (Super Admin, in the admin panel)

1. Create a staff user with role **ADMIN** (not SUPERADMIN), a strong password, and a **dashboard username**.
2. Grant **exactly** `MANAGE_PROJECTS`. Don't leave the permissions empty (that means full access, and the integration rejects it).
3. Approve or activate the account if your portal requires it.
4. Add its email to `CLAUDE_INTEGRATION_ALLOWEDEMAILS`.

### 9.4 Other requirements

| Item | Value |
|---|---|
| Database | MySQL 8 / MariaDB 10.4+ (tested on MariaDB 10.4.32). New tables `claude_integration_job`, `claude_integration_job_item`, `claude_integration_project_claim`: created by `ddl-auto=update` (dev setting), or run `migrations/add_claude_integration_tables.sql`. No existing table is altered. |
| Upload limits | Servlet multipart limit 300 MB (existing `LargeHttpUploadConfig`). The reverse proxy needs `client_max_body_size 300m` (existing `deploy/nginx-proxy-body-size.conf`). |
| Image storage | existing `upload_dir` property (`${upload_dir}properties/<slug>/`) |
| Job executor | bean `claudeImportExecutor`: 1 thread, queue 20; separate from the admin panel's import executor |
| JDK | Build target Java 17 (production image `eclipse-temurin:17`). Running and compiling also worked on JDK 21 and 25. **Run tests on JDK 17 or 21**: Mockito in this Spring Boot version cannot mock on JDK 25. |
| HTTPS | required for staging and production (credentials and JWT travel in requests) |

### 9.5 Start locally

```bash
cd mpf-backend
export CLAUDE_INTEGRATION_ENABLED=true
export CLAUDE_INTEGRATION_ALLOWEDEMAILS=<integration account email>
./mvnw spring-boot:run          # dev profile → http://localhost:8005
```

Run the tests:

```bash
JAVA_HOME=<jdk-21> ./mvnw test -Dtest='Claude*Test'                  # unit tests
CLAUDE_IT=true JAVA_HOME=<jdk-21> ./mvnw test -Dtest='Claude*Test'   # + DB tests on disposable schema mpf_claude_it only
```

---

## 10. Errors and troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| **401** `UNAUTHORIZED` on integration calls | missing, invalid or **expired** JWT (expiry verified by tests) | log in again (§3.2). Make sure the header is exactly `Authorization: Bearer <token>`. |
| 401 on **login** | wrong password; missing or wrong `dashboardUsername`; account pending approval | check the account in Manage Users |
| Malformed (not expired) JWT | handled by the existing JWT filter's error path; exact status and body **Needs verification** | log in again |
| **403** `FORBIDDEN` "MANAGE_PROJECTS permission is required" | account lacks the permission | grant `MANAGE_PROJECTS` |
| **403** "This account is not enabled for the Claude integration" | email not in `CLAUDE_INTEGRATION_ALLOWEDEMAILS` | add it and restart |
| **403** `ACCOUNT_TOO_PRIVILEGED` | SUPERADMIN, empty permissions, or extra permissions | dedicated ADMIN with exactly `MANAGE_PROJECTS` |
| **403** `INTEGRATION_ACCOUNT_RESTRICTED` | the integration account called an existing non-allowed API | by design; use only the integration API |
| **404** `NOT_FOUND` on every integration call | `CLAUDE_INTEGRATION_ENABLED` is not `true` | enable it and restart |
| **404** `JOB_NOT_FOUND` | wrong or mistyped `jobId` (jobs are never auto-deleted) | use the `jobId` from the import response |
| **409** `DUPLICATE_BATCH` | same files or `idempotencyKey` already accepted | poll the returned `jobId`; do not resubmit |
| **413** `FILE_TOO_LARGE` | file over its limit (Excel 20 MB, ZIP 300 MB, or proxy limit) | split the batch; check the proxy `client_max_body_size` |
| **422** `VALIDATION_FAILED` | see `validation` in the body | fix and validate again (§7 step 6) |
| **429** `RATE_LIMITED` | more than 10 validate/import calls per minute | wait 60 s |
| **503** `QUEUE_FULL` | 20 jobs already queued | retry later |
| `EXCEL_EXTENSION` / `EXCEL_NOT_A_WORKBOOK` / `EXCEL_UNREADABLE` | not a real .xlsx/.xls (renamed CSV, corrupt, password-protected) | save as .xlsx from Excel or a library |
| `EXCEL_MISSING_REQUIRED_HEADER` | header missing or misspelled | use the template headers |
| `EXCEL_UNKNOWN_HEADER` | extra column with values | remove it; keep provenance elsewhere |
| `UNKNOWN_AMENITY` | spelling differs from master (e.g. `Rainwater-Harvesting` vs `Rain Water Harvesting`) | use the hinted title |
| `UNKNOWN_CITY` / `UNKNOWN_STATE` / `UNKNOWN_COUNTRY`, `CITY_STATE_MISMATCH`, `STATE_COUNTRY_MISMATCH` | value missing from master data or in the wrong parent | use the existing names; a human adds new master data |
| `UNKNOWN_PROJECT_STATUS` / `UNKNOWN_PROPERTY_TYPE` | e.g. `New Launch` instead of `New Launched` | use a listed value (the message lists all) |
| Row `DUPLICATE_REVIEW_REQUIRED` "by RERA NO" | RERA already on another project (e.g. a township) | human review; never alter data to bypass |
| `ZIP_INVALID` / `ZIP_UNREADABLE` | not a ZIP, or corrupt/encrypted | rebuild the ZIP (standard deflate, no password) |
| `ZIP_UNSAFE_PATH`, `ZIP_SUSPICIOUS_RATIO`, `ZIP_EXTRACTED_TOO_LARGE`, `ZIP_TOO_MANY_ENTRIES` | unsafe or oversized archive | rebuild with relative paths; split the batch |
| `IMAGE_UNKNOWN_TYPE`, `IMAGE_BAD_NAME`, `IMAGE_DUPLICATE_TYPE`, `IMAGE_FOLDER_MISMATCH`, `IMAGE_PROJECT_NOT_IN_WORKBOOK` | naming errors | follow §6 |
| `IMAGE_CORRUPTED`, `IMAGE_INVALID_CONTENT`, `IMAGE_EXTENSION_MISMATCH`, `IMAGE_TOO_SMALL` | bad files | re-export the images |
| `IMAGE_CORE_MISSING` / `NO_IMAGES` (warnings) | Desktop, Mobile or Thumbnail missing / no ZIP | add them, or accept a draft without them |
| Job `FAILED`, summary "Job files are missing" | temp directory cleared before processing | resubmit (the key was released) |
| Job `FAILED`, "Interrupted by an application restart" | server restarted mid-job | check `results` (rows already created stay); resubmit the rest |
| Rows `FAILED` with `ROW_FAILED` | database error for that row (rolled back) | read the message; fix and resubmit that row |
| **Backend won't start on a fresh, empty database** (`UnexpectedRollbackException` from `BlogStatusCheckConstraintRunner`) | existing startup runner bug, unrelated to the integration | start from a copy of an existing database, or have the owners fix that runner |
| Backend can't connect to the database | wrong datasource URL or credentials, or DB not running | check `spring.datasource.*` / `SPRING_DATASOURCE_*` and the DB service |
| Mockito errors when running tests | JDK 25 | run tests on JDK 17 or 21 |

---

## 11. Reference: status values and issue codes

**Job status** (`ClaudeJobStatus`): `RECEIVED`, `VALIDATING`, `VALIDATION_FAILED`*, `QUEUED`, `PROCESSING`, `PARTIALLY_COMPLETED`*, `COMPLETED`*, `FAILED`* (*terminal)

**Row result** (`ClaudeItemStatus`): `PENDING`, `CREATED_DRAFT`, `DUPLICATE_REVIEW_REQUIRED`, `SKIPPED_VALIDATION`, `FAILED`

**Validation row status:** `VALID`, `BLOCKED`, `DUPLICATE_REVIEW_REQUIRED`

**Issue codes**

- *Workbook:* `EXCEL_NOT_A_WORKBOOK`, `EXCEL_NO_SHEETS`, `EXCEL_UNREADABLE`, `EXCEL_EXTRA_SHEETS` (W), `EXCEL_SHEET_NAME` (I), `EXCEL_NO_HEADER_ROW`, `EXCEL_NO_DATA_ROWS`, `EXCEL_TOO_MANY_ROWS`, `EXCEL_DUPLICATE_HEADER`, `EXCEL_VALUE_WITHOUT_HEADER`, `EXCEL_UNKNOWN_HEADER`, `EXCEL_UNKNOWN_EMPTY_HEADER` (W), `EXCEL_MISSING_REQUIRED_HEADER`, `EXCEL_FAQ_HEADER_UNPAIRED`
- *Row errors:* `REQUIRED_VALUE_MISSING`, `VALUE_TOO_LONG`, `INVALID_PROJECT_NAME`, `INVALID_BUILDER_NAME`, `UNKNOWN_COUNTRY`, `UNKNOWN_STATE`, `UNKNOWN_CITY`, `STATE_COUNTRY_MISMATCH`, `CITY_STATE_MISMATCH`, `UNKNOWN_PROJECT_STATUS`, `UNKNOWN_PROPERTY_TYPE`, `UNKNOWN_AMENITY`, `AMBIGUOUS_COUNTRY|STATE|CITY|BUILDER|AMENITY|PROJECT_STATUS|PROPERTY_TYPE`, `FAQ_INCOMPLETE`, `DUPLICATE_IN_WORKBOOK`, `IMAGE_UNKNOWN_TYPE`, `IMAGE_FOLDER_MISMATCH`, `IMAGE_DUPLICATE_TYPE`, `IMAGE_MULTIPLE_LOGO|LOCATION|THUMBNAIL`, `LOCATION_ADVANTAGE_UNPARSED` / `IMAGE_CORE_MISSING` (when strict)
- *Row warnings/info:* `NEW_BUILDER`, `BUILDER_MATCHED_BY_SLUG`, `BUILDER_DESCRIPTION_NOT_APPLIED`, `RERA_MISSING`, `RERA_WEBSITE_NOT_URL`, `IVR_NOT_PHONE`, `PRICE_BARE_NUMBER` (I), `CONFIGURATION_SEGMENT_UNPARSED`, `LOCATION_ADVANTAGE_UNPARSED`, `DUPLICATE_AMENITY`, `CITY_STATE_UNVERIFIED`, `DUPLICATE_TEXT_ACROSS_ROWS`, `TEXT_MENTIONS_OTHER_PROJECT`, `IMAGE_WILL_BE_UPSCALED`, `IMAGE_ASPECT_RATIO`, `IMAGE_CORE_MISSING`, `NO_IMAGES`, `STALE_CLAIM`
- *ZIP:* `ZIP_UNREADABLE`, `ZIP_TOO_MANY_ENTRIES`, `ZIP_EXTRACTED_TOO_LARGE`, `ZIP_SUSPICIOUS_RATIO`, `ZIP_UNSAFE_PATH`, `ZIP_UNEXPECTED_FILE`, `ZIP_DUPLICATE_FILE_NAME`, `ZIP_SYSTEM_FILE_IGNORED` (I), `IMAGE_INVALID_CONTENT`, `IMAGE_EXTENSION_MISMATCH`, `IMAGE_CORRUPTED`, `IMAGE_TOO_LARGE`, `IMAGE_TOO_SMALL`, `IMAGE_TOO_MANY_PIXELS`, `IMAGE_BAD_NAME`, `IMAGE_PROJECT_NOT_IN_WORKBOOK`
- *Job results:* `DUPLICATE_REVIEW_REQUIRED`, `ROW_FAILED`, `POST_IMPORT_CHECK`, `INVALID_AT_PROCESSING`, `BATCH_REFUSED`, `JOB_ABORTED`
- *HTTP-level `error` values:* `UNAUTHORIZED`, `FORBIDDEN`, `ACCOUNT_TOO_PRIVILEGED`, `NOT_FOUND`, `JOB_NOT_FOUND`, `RATE_LIMITED`, `DUPLICATE_BATCH`, `VALIDATION_FAILED`, `EXCEL_REQUIRED`, `EXCEL_EXTENSION`, `ZIP_EXTENSION`, `ZIP_INVALID`, `FILE_TOO_LARGE`, `UPLOAD_READ_FAILED`, `MISSING_PARAMETER`, `INVALID_MULTIPART`, `QUEUE_FULL`, `SUBMISSION_FAILED`, `STORAGE_ERROR`, `INVALID_PATH`, `VALIDATION_ERROR` (validate endpoint could not process the files, 400), `INTERNAL_ERROR`, plus `INTEGRATION_ACCOUNT_RESTRICTED` (from the filter)

---

## 12. Discrepancies with other documents

| Topic | Other document says | Actual implementation (this guide) |
|---|---|---|
| Architecture | `CLAUDE_INTEGRATION_ANALYSIS.md` §9–11 propose a Hibernate guard (D1-A) and calling `uploadProjectsExcel` | Superseded (as noted in that file's §0): a separate draft importer (D1-B) and an isolated image path |
| Brief's example image names `ProjectLogo`, `ProjectThumbnail`, `ProjectName-123` folders | used as examples in the original brief | `ProjectLogo` / `ProjectThumbnail` are **rejected**; `-123` is part of the project name, not an id |
| `X_Gallery_1.jpg`, `X_Gallery_2.jpg` style | suggested in the task brief | type token is `Gallery` for both → `IMAGE_DUPLICATE_TYPE`. Use `Gallery-1`, `Gallery-2`. |
| Image Studio sizes (2225×1065, 2700², 400², 815², 390×100) | original brief | images are re-encoded to fit 2508×1200, 800², 800², 1200×800, 792×203, 1600×1200 |
| Order of auth checks | `CLAUDE_INTEGRATION_API.md` §1 table lists "enabled → 404" before the allow-list but after the permission | same as here; note that `401`/`403` from checks 1–2 happen even when the integration is disabled |
| Token refresh | `CLAUDE_INTEGRATION_API.md` mentions refresh without caveat | `/api/v1/admin-portal/auth/refresh` requires a currently valid access token; re-login is the reliable path |
| Controller Javadoc of the existing `ExcelUploadController` | lists headers like `AMENITIES`, `ABOUT PROJECT DESCRIPTION` | not read by either importer; use the headers in §5.2 |
| `security.jwt.expiration-time` comment "1h" in `application-dev.properties` | 1 hour | value `86400000` = **24 hours** |
