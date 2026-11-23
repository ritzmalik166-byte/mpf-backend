# Claude Integration API

Base path: `/api/v1/integrations/claude`. All projects created through this API are **new, unpublished drafts** (`projects.status = false`). The API never updates, deletes or publishes an existing project, and never changes an existing builder.

## 1. Authentication

Use the existing admin login. There is no separate credential.

```http
POST /api/v1/admin-portal/auth/login
Content-Type: application/json

{"email": "<integration account email>", "password": "<password>", "dashboardUsername": "<dashboard username>"}
```

`200` → `{"token": "<JWT>", "refreshToken": "…", "expiresIn": 86400000, "user": {…}}` · `401` → `{"message": "…"}`

Send `Authorization: Bearer <JWT>` on every call below. Never send the password again. When the token expires, log in again, or refresh via `POST /api/v1/admin-portal/auth/refresh` with `{"refreshToken": "…"}`.

Each connector call passes these checks, in order:

| Check | Failure |
|---|---|
| Valid JWT | `401 UNAUTHORIZED` (also for expired tokens) |
| Existing `MANAGE_PROJECTS` permission | `403 FORBIDDEN` |
| Connector enabled (`CLAUDE_INTEGRATION_ENABLED=true`) | `404 NOT_FOUND` |
| Account email in `CLAUDE_INTEGRATION_ALLOWEDEMAILS` | `403 FORBIDDEN` |
| Least privilege: not SUPERADMIN, and explicit permissions are exactly `MANAGE_PROJECTS` | `403 ACCOUNT_TOO_PRIVILEGED` |
| Rate limit (validate/import), default 10/min per account | `429 RATE_LIMITED` |

**Restriction on existing APIs.** Requests authenticated as an allow-listed integration account are allowed only for this API, the login/session/refresh/logout endpoints, and `GET /api/v1/projects/admin/get/{slug}` and `GET /api/v1/projects/admin/get-by-id/{id}`. Everything else returns `403 INTEGRATION_ACCOUNT_RESTRICTED`, including project edit and delete, and the existing Excel upload.

## 2. Error format

```json
{"timestamp": "2026-11-23T07:00:00Z", "status": 400, "error": "EXCEL_EXTENSION", "message": "excelFile must be .xlsx or .xls"}
```

Responses never include stack traces or server file paths.

## 3. Endpoints

### 3.1 `POST /properties/validate`: dry run

Writes nothing: no job, no project, no builder, no file.

| Multipart field | Required | Notes |
|---|---|---|
| `excelFile` | yes | `.xlsx`/`.xls`. Content is sniffed. Max 20 MB. |
| `imagesZip` | no | `.zip`. Content is sniffed. Max 300 MB compressed / 1 GB extracted / 2000 entries. |

`200` → `ClaudeValidationReport`:

```json
{
  "valid": false,
  "importableRows": 0, "blockedRows": 20, "duplicateRows": 0, "totalRows": 20,
  "errorCount": 21, "warningCount": 80,
  "_note": "Illustrative excerpt shaped like the reference-workbook result: row/status counts are real, issue counts are approximate. Row 4 is also a RERA duplicate but counts as blocked because it has errors.",
  "workbook": {
    "filename": "Property List-1 - Copy (1).xlsx", "format": "xlsx", "sheetName": "Sheet1", "sheetNames": ["Sheet1"],
    "headerRow": 1, "firstDataRow": 2, "lastDataRow": 21, "dataRows": 20,
    "detectedHeaders": [
      {"column": "A", "rawHeader": "ID", "normalizedHeader": "ID", "mappedTo": "Not stored on the project…", "status": "REFERENCE_ONLY"},
      {"column": "F", "rawHeader": " PROJECT CONFIGURATION", "normalizedHeader": "PROJECT CONFIGURATION", "mappedTo": "projects.project_configuration + floor_plans…", "status": "MAPPED"}
    ],
    "missingRequiredHeaders": [], "unknownHeaders": [],
    "unsupportedFields": {"ID": "…", "BUILDER DESCRIPTION": "…", "LOCATION ADVANTAGE": "…"}
  },
  "zip": {"filename": "properties_images.zip", "entries": 140, "images": 140, "extractedBytes": 81234567, "issues": []},
  "workbookIssues": [],
  "rows": [
    {
      "excelRow": 5, "sourceReference": "4", "projectName": "Global City Walk", "builderName": "Garg Realtors",
      "cityName": "Ghaziabad", "reraNo": "UPRERAPRJ958302", "predictedSlug": "global-city-walk",
      "status": "BLOCKED", "newBuilder": true, "amenitiesMatched": ["24 x 7 Water Supply", "…"],
      "floorPlans": 1, "locationBenefits": 0, "faqs": 4, "images": [],
      "errors": [
        {"severity": "ERROR", "code": "UNKNOWN_PROJECT_STATUS", "message": "PROJECT STATUS NAME \"New Launch\" does not exist. Existing values: …", "excelRow": 5, "column": "PROJECT STATUS NAME", "value": "New Launch"},
        {"severity": "ERROR", "code": "UNKNOWN_AMENITY", "message": "Amenity \"Rainwater-Harvesting\" does not match any existing amenity (did you mean \"Rain Water Harvesting\"?)…", "excelRow": 5, "column": "PROJECT AMENITIES", "value": "Rainwater-Harvesting"}
      ],
      "warnings": [{"severity": "WARNING", "code": "NEW_BUILDER", "…": "…"}],
      "unappliedFields": {"LOCATION ADVANTAGE": "Global City Walk by Garg Realtors is located in …"}
    }
  ]
}
```

Row `status`: `VALID` · `BLOCKED` (has ERROR issues) · `DUPLICATE_REVIEW_REQUIRED` (matches an existing project by slug, RERA, or name+builder+city, or was already imported by Claude; `existingProjectId` and `duplicateReasons` are set).

`valid` is `true` only when there are no errors and no duplicates anywhere.

### 3.2 `POST /properties/import`: create drafts

| Multipart field | Required | Default | Notes |
|---|---|---|---|
| `excelFile` | yes | | as above |
| `imagesZip` | no | | as above |
| `batchName` | no | | label, max 200 chars |
| `dryRun` | no | `false` | `true` returns the validation report (`200`) and writes nothing |
| `allowPartial` | no | `false` | `false`: any blocked or duplicate row refuses the whole batch. `true`: valid rows are imported and the others are recorded. |
| `idempotencyKey` | no | SHA-256 of the files | your own key per batch, scoped to your account |

Responses:

| Status | When | Body |
|---|---|---|
| `202` | queued | `{"jobId", "status": "QUEUED", "message", "statusUrl", "resultsUrl", "validation": {…}}` |
| `200` | `dryRun=true` | `{"dryRun": true, "message", "validation": {…}}` |
| `400` | missing or invalid file (wrong extension or content) | error |
| `409` | the same batch (same files or same `idempotencyKey`) was already accepted | `{"error": "DUPLICATE_BATCH", "jobId", "status", "statusUrl"}` |
| `413` | file over its limit | error |
| `422` | validation refused the batch (workbook/ZIP errors, no importable rows, or row problems without `allowPartial`) | `{"jobId", "status": "VALIDATION_FAILED", "message", "validation": {…}}`. A job record is kept and the same files may be resubmitted after fixes. |
| `503` | import queue full | error |

### 3.3 `GET /jobs/{jobId}`

```json
{
  "jobId": "7f0c…", "batchName": "November_Property_Batch", "status": "PARTIALLY_COMPLETED", "allowPartial": true,
  "totalProperties": 4, "successfulProperties": 1, "failedProperties": 0, "duplicateProperties": 3,
  "skippedProperties": 0, "pendingProperties": 0,
  "originalExcelFilename": "properties.xlsx", "originalZipFilename": "properties_images.zip",
  "createdAt": "2026-11-23T12:18:40", "startedAt": "2026-11-23T12:18:40", "completedAt": "2026-11-23T12:18:41",
  "errorSummary": "Created drafts: 1, duplicates: 3, skipped (validation): 0, failed: 0",
  "statusUrl": "/api/v1/integrations/claude/jobs/7f0c…", "resultsUrl": "/api/v1/integrations/claude/jobs/7f0c…/results"
}
```

`404 JOB_NOT_FOUND` for an unknown id.

### 3.4 `GET /jobs/{jobId}/results`

`{"job": {…as above…}, "results": [ClaudePropertyResult…]}`. One entry per Excel row:

```json
{
  "excelRow": 2, "sourceReference": "1", "projectName": "The Core Ultra Wide", "builderName": "Concept Capital Infra",
  "cityName": "Ghaziabad", "reraNo": "UPRERAPRJ9641",
  "projectId": 1402, "slug": "the-core-ultra-wide",
  "draft": true, "verified": true,
  "builderId": 512, "builderCreated": true,
  "imagesExpected": 7, "imagesImported": 7,
  "importStatus": "CREATED_DRAFT",
  "errors": [], "warnings": [ … ], "unappliedFields": {"LOCATION ADVANTAGE": "…"}
}
```

| `importStatus` | Meaning |
|---|---|
| `PENDING` | waiting for the job thread |
| `CREATED_DRAFT` | new draft created. `draft` and `verified` are read back from the database. If `errors` contains `POST_IMPORT_CHECK` entries (for example missing images), the draft exists but needs review. |
| `DUPLICATE_REVIEW_REQUIRED` | nothing written; `existingProjectId` set where identifiable |
| `SKIPPED_VALIDATION` | row had blocking errors (or the batch was refused); nothing written |
| `FAILED` | the row's transaction was rolled back; nothing from that row remains |

### 3.5 `GET /template`

`?format=xlsx` (default) downloads `mpf-claude-property-template.xlsx`: Sheet1 with the 32 reference headers, a "Column guide" sheet and an "Image names" sheet. `?format=json` returns the same information as JSON.

## 4. Job lifecycle

```
RECEIVED → VALIDATING → VALIDATION_FAILED                                (422, nothing written)
                      → QUEUED → PROCESSING → COMPLETED                  (every row a verified draft, no errors)
                                            → PARTIALLY_COMPLETED        (≥1 draft created; other rows duplicate/skipped/failed or with post-import errors)
                                            → FAILED                     (no draft created)
```

Jobs and results are stored in `claude_integration_job` / `claude_integration_job_item` and survive restarts. After a restart, `QUEUED` jobs whose files are still present run again. Jobs that were mid-validation or mid-processing are closed as `FAILED` ("Interrupted by an application restart"). Drafts already created stay and are listed.

At processing time the stored files are **re-validated against the current database**. A row that became a duplicate or invalid after submission is recorded as such instead of being written.

## 5. Example session (curl)

```bash
API=https://staging.example.com/api/v1
TOKEN=$(curl -s -X POST $API/admin-portal/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$MPF_EMAIL\",\"password\":\"$MPF_PASSWORD\",\"dashboardUsername\":\"$MPF_DASHBOARD_USER\"}" | jq -r .token)

curl -s -H "Authorization: Bearer $TOKEN" -F excelFile=@properties.xlsx -F imagesZip=@properties_images.zip \
  $API/integrations/claude/properties/validate | jq '{valid, importableRows, blockedRows, duplicateRows}'

JOB=$(curl -s -H "Authorization: Bearer $TOKEN" -F excelFile=@properties.xlsx -F imagesZip=@properties_images.zip \
  --form-string batchName=November_Property_Batch $API/integrations/claude/properties/import | jq -r .jobId)

curl -s -H "Authorization: Bearer $TOKEN" $API/integrations/claude/jobs/$JOB | jq .status
curl -s -H "Authorization: Bearer $TOKEN" $API/integrations/claude/jobs/$JOB/results | jq '.results[] | {excelRow, projectId, slug, draft, verified, importStatus}'
```

## 6. Issue codes (most common)

Workbook: `EXCEL_NOT_A_WORKBOOK`, `EXCEL_UNREADABLE`, `EXCEL_NO_HEADER_ROW`, `EXCEL_NO_DATA_ROWS`, `EXCEL_TOO_MANY_ROWS`, `EXCEL_DUPLICATE_HEADER`, `EXCEL_UNKNOWN_HEADER` (blocking when the column has values), `EXCEL_MISSING_REQUIRED_HEADER`, `EXCEL_VALUE_WITHOUT_HEADER`, `EXCEL_FAQ_HEADER_UNPAIRED`.

Row: `REQUIRED_VALUE_MISSING`, `VALUE_TOO_LONG`, `UNKNOWN_COUNTRY|STATE|CITY`, `STATE_COUNTRY_MISMATCH`, `CITY_STATE_MISMATCH`, `UNKNOWN_PROJECT_STATUS`, `UNKNOWN_PROPERTY_TYPE`, `UNKNOWN_AMENITY`, `AMBIGUOUS_*`, `FAQ_INCOMPLETE`, `DUPLICATE_IN_WORKBOOK`, `IMAGE_*`. Warnings: `NEW_BUILDER`, `BUILDER_DESCRIPTION_NOT_APPLIED`, `BUILDER_MATCHED_BY_SLUG`, `LOCATION_ADVANTAGE_UNPARSED`, `CONFIGURATION_SEGMENT_UNPARSED`, `RERA_MISSING`, `DUPLICATE_TEXT_ACROSS_ROWS`, `TEXT_MENTIONS_OTHER_PROJECT`, `IMAGE_WILL_BE_UPSCALED`, `IMAGE_ASPECT_RATIO`, `IMAGE_CORE_MISSING`, `NO_IMAGES`, `STALE_CLAIM`.

ZIP: `ZIP_UNREADABLE`, `ZIP_TOO_MANY_ENTRIES`, `ZIP_EXTRACTED_TOO_LARGE`, `ZIP_SUSPICIOUS_RATIO`, `ZIP_UNSAFE_PATH`, `ZIP_UNEXPECTED_FILE`, `ZIP_DUPLICATE_FILE_NAME`, `IMAGE_INVALID_CONTENT`, `IMAGE_EXTENSION_MISMATCH`, `IMAGE_CORRUPTED`, `IMAGE_TOO_LARGE`, `IMAGE_TOO_SMALL`, `IMAGE_TOO_MANY_PIXELS`, `IMAGE_BAD_NAME`, `IMAGE_PROJECT_NOT_IN_WORKBOOK`, `IMAGE_UNKNOWN_TYPE`, `IMAGE_FOLDER_MISMATCH`, `IMAGE_DUPLICATE_TYPE`, `IMAGE_MULTIPLE_LOGO|LOCATION|THUMBNAIL`.

Job results: `DUPLICATE_REVIEW_REQUIRED`, `ROW_FAILED`, `POST_IMPORT_CHECK`, `INVALID_AT_PROCESSING`, `BATCH_REFUSED`, `JOB_ABORTED`.
