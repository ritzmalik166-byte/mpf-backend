# Claude Integration: Codebase Analysis (Phase 1) and Architecture Proposal (Phase 2)

Status: **implemented (Phase 3), with zero modifications to existing files.** Sections 1–8 record the analysis of the existing code. Section 0 records the final decisions and **supersedes the proposal in sections 9–11** wherever they differ (notably, D1-A was **not** implemented; D1-B was).

## 0. Final decisions and what was built

| Decision | Final | Implementation |
|---|---|---|
| D1: draft enforcement | **B: separate draft importer** | `importer/ClaudeDraftProjectImporter`. It never calls `ProjectExcelUploadService`. New `Project` objects get `status=false` before `persist`, and nothing in the module ever sets it to `true`. The inserted row is read back with SQL in the same transaction; anything but a draft rolls the row back. No Hibernate listener or global hook (option A was dropped). |
| D1: images | **Isolated image path** | `importer/ClaudeImageAttachmentService` + `ClaudeImageRowWriter`. The existing image step is private, and its only public entry point (`uploadProjectsExcel`) also runs project creation/update, so it is **not** used. The connector reuses only the public primitive `FileUtils.saveImageFromBytes`, so files are resized and encoded identically, with the same folder layout, file naming and alt tags. |
| D2: master data | As specified | City, state, country, status and type must exist. There is no auto-create and no "first row" fallback. City must belong to STATE NAME, and state to COUNTRY NAME. Every amenity must match (case/space/hyphen-insensitive, records untouched). A builder may be created (name, slug and, for a new builder only, the description). Existing builders are never written. |
| D3: image sizes | Existing behaviour kept | Same fit-within boxes as the admin importer. The validator warns when an image will be upscaled or has a different aspect ratio. |
| D4: template | **Reference workbook `Property List-1 - Copy (1).xlsx`** | 32 headers A–AF, Sheet1, rows 2–21. AG is blank and ignored. Matching is case/space-insensitive. `ID` is a reference only. `BUILDER DESCRIPTION` goes to `builders.builder_desc` only for builders this import creates; otherwise it is reported and kept in the job result. |
| D5: tests | Mockito + disposable MySQL schema | 47 unit tests + 13 DB tests against `mpf_claude_it`. No `pom.xml` change. See `CLAUDE_INTEGRATION_TEST_REPORT.md`. |
| Package | `com.mypropertyfact.estate.integration.claude` | Under the existing component-scan root, so no config change is needed. |
| Security | Existing JWT login + MANAGE_PROJECTS + allow-list + least-privilege check + restriction filter | `ClaudeIntegrationAccountRestrictionFilter` blocks allow-listed integration accounts from every existing API except login/session and the read-only admin project GETs. For any other account it does nothing. |
| Concurrency | DB-backed identity claims | New table `claude_integration_project_claim` with a unique `claim_key` (`SLUG:`, `RERA:`, `NBC:`, `BUILDER:`), written in the same transaction as the draft. No constraint is added to existing tables. |

### What the reference workbook revealed (validated against the local dev DB's master data)

| Row(s) | Finding | Effect |
|---|---|---|
| 2–21 (all) | `PROJECT Amenities` contains `Rainwater-Harvesting`; the master title is `Rain Water Harvesting` | **Blocking** (`UNKNOWN_AMENITY`, with a suggestion). The existing importer would silently drop it. |
| 5 | `PROJECT STATUS NAME` = `New Launch`; the master has `New Launched` | **Blocking** (`UNKNOWN_PROJECT_STATUS`). The existing importer would fall back silently. |
| 4 | `RERA NO` `95 OF 2017` already belongs to project #1373 "Central Park Flower Valley Mikasa Plots" | `DUPLICATE_REVIEW_REQUIRED` |
| 2–21 (all) | `LOCATION ADVANTAGE` is prose, not `Name-4.2-Km` segments | Warning; nothing can be stored as location benefits. The text is kept in the job result (`unappliedFields`). Make it blocking with `CLAUDE_INTEGRATION_STRICTLOCATIONADVANTAGE=true`. |
| 20, 21 | `LOCATION ADVANTAGE` is identical to row 19 and describes **Raheja Maheshwara** | Warnings `DUPLICATE_TEXT_ACROSS_ROWS` + `TEXT_MENTIONS_OTHER_PROJECT`. This is likely a copy/paste error. |
| 3, 4, 10, 13, 15, 19, 20 | Builder already exists | `BUILDER DESCRIPTION` is not applied (warning, value kept) |
| other 13 rows | Builder does not exist | New builder created with name, slug and description (warning) |
| 2 | Configuration `Studo Apartment-525 sq.ft` (typo for "Studio") | Stored as given (floor plan type "Studo Apartment") |
| all | `IVR NO` numeric → `8920024793`; `PROJECT PRICE` numeric → `0.75` (no unit) | Stored as text exactly as the existing importer would (info message) |

With the amenity and status values corrected, the validator reports **19 importable rows and 1 duplicate (row 4)**. This is covered by `ClaudeBatchValidatorTest.referenceWorkbookBecomesImportableOnceMasterDataMatches`.

### Pre-existing issue found during testing (not changed)

`config/BlogStatusCheckConstraintRunner` cannot complete on a **fresh** database. Its `ALTER TABLE blogs DROP CHECK …` fails (MariaDB syntax differs). The exception is caught, but the transaction is already marked rollback-only, so application startup fails with `UnexpectedRollbackException`. This affects any brand-new staging database, independent of this integration. The DB tests replace that runner with a no-op mock in their own context only.

---

Analysed: `mpf-backend` (Spring Boot 3.3.5, Java 17 target, Hibernate 6, MySQL, Apache POI 5.2.5), plus the admin bulk uploader in `my-property-fact` (read only, to confirm the client contract).

---

## 1. Existing Excel import architecture

| Item | Finding (source) |
|---|---|
| Controller | `controllers/ExcelUploadController.java`, base path `/api/v1/excel-upload` |
| Service | `services/ProjectExcelUploadService.java` (1015 lines). This one class does everything: parsing, row mapping, the ZIP and the images. |
| Library | Apache POI `WorkbookFactory`, which reads both `.xlsx` and `.xls` |
| File check | Checks the filename extension only (`.xlsx` / `.xls`). The content is not checked. |
| Sheet | **Always the first sheet (`getSheetAt(0)`).** The sheet name is never checked, so `Sheet1` is not required. |
| Header matching | By header **name**, not by column position. Each header is trimmed, upper-cased and has its whitespace collapsed (`buildHeaderIndex`). Column order does not matter. Unknown headers are **silently ignored**. If a header appears twice, the last one wins. |
| Multiple projects | Yes. Each data row is one project, processed in its own `REQUIRES_NEW` transaction (`processProjectRowInNewTransaction`). One bad row does not roll back the others. |
| Project identity | **Only `generateSlug(PROJECT NAME)`**, through `projectRepository.findBySlugURL`. There is no ID column, and RERA, builder and city play no part. |
| Existing project | **Updated in place.** Every non-blank cell overwrites its field. FAQs, floor plans and location benefits are **deleted and replaced**. Amenities are added. The walkthrough is only written if none exists. The about description is overwritten. |
| New project | `project.setStatus(true)` (line 786). **New projects are published immediately.** `showFeaturedProperties=false`. `createdBy` = current security-context user. |
| Blank cells | Ignored by `setIfPresent`, so blank cells do not wipe data (good). The exception is FAQs: they are always deleted first, so a row with no FAQ columns **deletes all FAQs** of an existing project. |
| Master data | Builder, country, state and city are **find-or-create**: a misspelled city or builder creates a new master row. Missing country defaults to `India`. Missing state defaults to `"N/A"`. An unknown status falls back to "Under Construction", then to the **first status in the table**. An unknown property type falls back to the **first type in the table**. |
| Errors | Each row error is a string (`"Row N: reason"`). The response is `Response{isSuccess:1, message:"Upload completed. Created: x, Updated: y ... Errors: n - <first 10>"}`. `isSuccess` is 1 even when rows fail. |
| Sync endpoint | `POST /api/v1/excel-upload/projects` |
| Async endpoint | `POST /api/v1/excel-upload/projects/async` plus `GET /projects/jobs/{jobId}`. Jobs are held **in memory** (`ConcurrentHashMap`), dropped 1 hour after they finish, and lost on restart. Executor `projectImportJobExecutor`: core 1, max 2, queue 5. |
| Publishing | **Yes. Every new project is published (`status=true`).** |

### Columns the importer actually reads (from source, not documentation)

| Header (case/space-insensitive) | Target |
|---|---|
| `PROJECT NAME` (required) | `projects.project_name`, slug |
| `BUILDER NAME` (required) | `builder` (find-or-create) |
| `CITY NAME` (required) | `city` (find-or-create) |
| `STATE NAME` | `state` (find-or-create, default `N/A`) |
| `COUNTRY NAME` | `country` (find-or-create, default `India`) |
| `PROJECT STATUS NAME` | `project_status` lookup (with fallback) |
| `PROPERTY TYPE NAME` | `project_types` lookup (with fallback) |
| `PROJECT LOCALITY` | `project_locality` |
| `PROJECT CONFIGURATION` | `project_configuration` + `floor_plans` rows parsed from `"3 BHK-1333 sq.ft, 4 BHK-1744 sq.ft"` |
| `PROJECT PRICE` | `project_price` (free text, not numeric) |
| `RERA NO` | `rera_no` |
| `RERA WEBSITE` | `rera_website` |
| `IVR NO` | `ivr_no` |
| `PROJECT ABOUT SHORT DESCRIPTION` | `projects_about.long_desc` |
| `PROJECT WALKTHROUGH DESCRIPTION` | `project_walkthrough` (only if none exists) |
| `FLOOR PLAN DESCRIPTION` | `floor_plan_desc` |
| `LOCATION DESCRIPTION` | `location_desc` |
| `AMENITY DESCRIPTION` | `amenity_desc` |
| `PROJECT AMENITIES` (fallback `PROJECT Amenities`) | links to **existing** `amenity` rows by title. Separators `_`, `|` or `,`; `-` becomes a space. **Unknown amenities are silently dropped.** |
| `LOCATION ADVANTAGE` | `location_benefit` rows. Formats `Name-Words-4.2-Km` or `Name (4.2 Km)`, separated by `_`, `|` or `,`. **Segments that don't match are silently dropped.** |
| `META TITLE`, `META DESCRIPTION`, `META KEYWORD` | SEO fields |
| `Question 1`/`Answer 1` … `Question 20`/`Answer 20` | `project_faqs` |

**Not read by the importer at all:** `S NO`, `BUILDER DESCRIPTION`, `PROPERTY TYPE`, `AMENITIES`, `LOCATION MAP DESCRIPTION`, `ABOUT PROJECT DESCRIPTION`, and the image-filename columns. The controller Javadoc lists these, but it is out of date with the code.

The admin UI template (`bulkProjectImport.js` → `TEMPLATE_HEADERS`) has 27 columns, A–AA, and only 2 FAQ pairs. **The `Property_List_SEO_Pilot_v4` workbook was not found on this machine**, so its 32 headers could not be verified. Its "builder description" column (R–X range) has **no matching importer column** and would be silently dropped. See decision D4.

---

## 2. Existing ZIP image import architecture

| Item | Finding |
|---|---|
| Endpoint | Same request as the Excel import: multipart field `imagesZip` (optional) on `/api/v1/excel-upload/projects[/async]` |
| Extraction | `extractZipToMap`. The whole ZIP is read **into memory** as `Map<basename, bytes>`. **Folders are flattened** (only the file basename is kept). Two files with the same basename in different folders **overwrite each other**. |
| ZIP safety | ZIP Slip is not possible, because nothing is written using entry paths. There is **no limit on entry count or extracted size**, so a ZIP bomb would exhaust memory. A ZIP read failure is only logged as a warning; the import continues with no images. |
| Header-only Excel | Allowed when a ZIP is present. In that case only the images are processed (this is how the UI's batch uploader sends image-only batches). |
| Filename convention | `<ProjectKey>_<ImageType>[_anything].<ext>`, split on `_`. Part 0 is the project key, part 1 is the image type, and everything after is ignored. |
| Project key → project | `key.replace("-", " ")`, then `generateSlug(...)`, then `findBySlugURL`, falling back to `findFirstByProjectNameIgnoreCase`. The key is **the project name with hyphens instead of spaces**. It is **not a project ID**: `ProjectName-123` resolves to the project *named* "ProjectName 123". |
| Image type (prefix match, case-insensitive) | `desktop*` → desktop banner · `mobile*` → mobile banner · `logo*` → `projects.project_logo` · `location*` → `projects.location_map` · `thumbnail*` → `projects.project_thumbnail` · **anything else → gallery** |
| Content check | Magic-byte sniffing for PNG/JPG/GIF/WEBP, then `ImageIO.read`. A corrupt image fails silently (`null`) and is not counted. |
| Resize | **Every image is resized and re-encoded**: scaled to fit within the box (aspect kept, small images upscaled and sharpened), quality 0.98. |
| Target boxes | Desktop 2508×1200 · Mobile 800×800 · Thumbnail 800×800 · Logo 792×203 · Location 1200×800 · Gallery 1600×1200 |
| Duplicates | Logo, location and thumbnail are skipped if the project already has one. Banners and gallery images are skipped if an image with the same alt tag (`"<Project Name> - <type>"`) already exists. |
| Storage | `${upload_dir}properties/<slug>/<timestamp>_<type>_<uuid8>.<ext>`. Images are resized in parallel on `projectImageExecutor`. DB rows are inserted per project in one `REQUIRES_NEW` transaction. |
| Failure | If an image file is written to disk but its DB insert fails, the **orphan file is left on disk**. The failure is logged only. |

### Discrepancies with the brief's illustrative naming (important)

| Brief example | What the existing importer does |
|---|---|
| `…_ProjectLogo_…` | starts with `project` → saved as a **gallery image**, not a logo |
| `…_ProjectThumbnail_…` | → **gallery image** |
| `…_LocationMap_…` | → location map ✓ |
| `…_DesktopBanner_…`, `…_MobileBanner_…` | → desktop / mobile ✓ |
| `…_Gallery1_…` | → gallery ✓ |
| `ProjectName-123/` folder, `-123` suffix | folder ignored; `-123` becomes part of the name lookup and **won't match** unless the project's name ends in "123" |

Correct names: `<Project-Name-With-Hyphens>_Logo_My-Property-Fact.png`, `_Thumbnail_`, `_Location_`, `_Desktop-1_`, `_Mobile-1_`, `_Gallery-1_`, and so on.

### Image Studio sizes vs importer sizes

The brief's sizes (desktop 2225×1065, mobile 2700×2700, thumbnail 400×400, location 815×815, logo 390×100) **don't match the importer**. The importer re-encodes every image into its own boxes (2508×1200, 800×800, 800×800, 1200×800, 792×203). A 390×100 logo would be upscaled ~2× and sharpened. A 2700² mobile banner would be downscaled to 800². Reusing the importer as-is means **resizing and re-encoding can't be avoided**. See decision D3.

---

## 3. Existing project creation flow and DB relationships

`projects` (entity `Project`, IDENTITY int PK). Columns relevant here: `project_name`, `slugurl`, `status` (boolean = published), `show_featured_properties`, `rera_no`, `rera_website`, `project_logo`, `project_thumbnail`, `location_map`, SEO fields, `created_by_user_id`, `created_at`/`updated_at` (set by `@PrePersist`/`@PreUpdate`).

- Many-to-one: `city_Id`→`city`(→`state`→`country`), `state_id` (not set by the importer), `builder_id`, `property_type`→`project_types`, `project_status_id`.
- Many-to-many: `project_amenities` (amenity).
- One-to-many (FK `project_id`): `project_gallery` (also carries `slug_url`), `project_desktop_banner`, `project_mobile_banner`, `project_banner`, `floor_plans`, `projects_about`, `project_walkthrough`, `location_benefit`, `project_faqs` (also `slug_url`).
- Indexes on `projects`: `created_at`, `updated_at`, `created_by_user_id`, `status`. **There is no unique constraint on `slugurl` or `rera_no`.** Uniqueness is enforced only in application code (`findBySlugURL` returns an `Optional`, so duplicate slugs would make it throw).
- Slug: `FileUtils.generateSlug`. It strips everything except `[A-Za-z0-9\s-]`, turns whitespace into `-`, collapses repeated `-`, trims, and lower-cases. **Phases with the same name but different RERA numbers collide**: "X Phase 2" and "X Phase-2" produce the same slug.
- Public vs admin reads: the public `GET /api/v1/projects/get/{url}` filters `status = true`. The admin `GET /api/v1/projects/admin/get/{url}` and `/admin/get-by-id/{id}` (MANAGE_PROJECTS) don't filter. **Note:** the public `GET /api/v1/projects` (`findAllProjects`) returns **all projects, including drafts**, with a `status` field. Hiding drafts there depends on the frontend. This is existing behaviour and is out of scope.
- Migrations: **no Flyway or Liquibase.** Schema comes from `spring.jpa.hibernate.ddl-auto=update`, plus hand-run SQL in `migrations/*.sql`.

---

## 4. Existing authentication and authorization

- Login: `POST /api/v1/admin-portal/auth/login` (or `/api/v1/auth/login`). Body `LoginUserDto{email, password, dashboardUsername}`. **Staff (ADMIN) accounts must send `dashboardUsername`**, and it must match the value set in Manage Users. The response is `LoginResponse{token, refreshToken, expiresIn, user}` and it also sets `token`/`refreshToken` cookies. Login does **not** bump `tokenVersion`, so several sessions can coexist.
- JWT: `JwtAuthenticationFilter` reads `Authorization: Bearer` or the `token` cookie. For staff, the token's `tokenVersion` must equal the user's current one. Access token TTL = `security.jwt.expiration-time` (86400000 ms = 24 h in dev). Refresh: `POST /api/v1/admin-portal/auth/refresh`.
- URL rules: `SecurityConfiguration` ends with `anyRequest().permitAll()`. **Protection for project APIs comes entirely from method-level `@PreAuthorize("@adminPermissionService.can(authentication,'MANAGE_PROJECTS')")`.** New controllers must use the same annotation.
- Permissions: `AdminPermissionService.can`. SUPERADMIN gets everything. ADMIN is checked against `user.adminPermissions`. **If an ADMIN has no permission rows at all, they get full legacy CMS access** (`app.admin.permissions-legacy-full-access-when-empty=true` by default). So the integration user **must** be given exactly `MANAGE_PROJECTS` explicitly, never left empty.
- `MANAGE_PROJECTS` is the narrowest key available. It also allows edit, delete and amenity changes through the existing project APIs. A narrower key would require editing `AdminPermissionKeys` (an existing file). Mitigation: the new endpoints also check an allow-list of integration emails (see section 9).
- `AccessDeniedException` is handled by `GlobalExceptionHandler` (403 JSON).

---

## 5. Existing API contracts (relevant subset)

| Method & URL | Auth | Request | Response |
|---|---|---|---|
| `POST /api/v1/admin-portal/auth/login` | none | JSON `{email,password,dashboardUsername}` | `{token,refreshToken,expiresIn,user}` / 401 `{message}` |
| `POST /api/v1/excel-upload/projects` | MANAGE_PROJECTS | multipart `file` (xlsx/xls), `imagesZip` (opt) | `Response{isSuccess,message}` |
| `POST /api/v1/excel-upload/projects/async` | MANAGE_PROJECTS | same | `ProjectImportProgress{jobId,phase,rowsTotal,rowsDone,imagesTotal,imagesDone,done,isSuccess,message,created,updated,imagesSaved,imagesSkipped,errors[]}` |
| `GET /api/v1/excel-upload/projects/jobs/{jobId}` | MANAGE_PROJECTS | — | same / 404 |
| `GET /api/v1/projects/admin/get-by-id/{id}` | MANAGE_PROJECTS | — | `ProjectFullDetails` (drafts included) |
| `GET /api/v1/projects/admin/get/{slug}` | MANAGE_PROJECTS | — | `ProjectFullDetails` |
| `GET /api/v1/projects/get/{slug}` | public | — | published only |

Upload limits: 300 MB (`LargeHttpUploadConfig` defines a `MultipartConfigElement` bean, which overrides the 10 MB dev property).

---

## 6. Reusability of existing service methods (from source)

| Member | Visibility | Usable from a new package? |
|---|---|---|
| `uploadProjectsExcel(MultipartFile, MultipartFile)` | public | **Yes.** It runs the full existing pipeline on the calling thread. |
| `startProjectsImport(...)` / `getImportJob(...)` | public | Yes, but its results live in memory only and its executor is shared with the admin panel. |
| `processProjectRowInNewTransaction(Row, Map, String, int)` | public | It can be called, but it returns a `private static` class, so the outcome can only be read by reflection. |
| `saveProjectImagesInNewTransaction(int, String, List<SavedImage>)` | public | Not practical: `SavedImage` and `PlannedImage` are package-private records. |
| `runImport`, `processZipImages`, `resolveOrCreateProject`, `extractZipToMap`, the parsers | private | No |
| `FileUtils.generateSlug` | public | Yes (used to predict slugs exactly) |
| Repositories | public interfaces | Yes, read-only lookups. New query methods go in a **new** repository interface, not in existing ones. |

**Conclusion:** the existing importer can be reused unchanged through its public `uploadProjectsExcel` method, without an HTTP self-call, by handing it in-memory `MultipartFile`s. It cannot be called in a way that creates drafts or refuses updates. Both are hard-coded (status=true at line 786, update-on-slug-match at line 776). These are the two zero-modification blockers.

---

## 7. Existing limitations relevant to the integration

1. New projects are published immediately (`status=true`).
2. A slug match silently **updates** an existing project, including deleting its FAQs, floor plans and location benefits.
3. Identity is the name slug only. RERA, builder, city and phase are ignored, so distinct phases can collide.
4. Master data is created on any typo, and unknown status or type falls back to an arbitrary first row.
5. Unknown headers, unknown amenities and location-advantage segments that don't parse are silently dropped.
6. The ZIP is read fully into memory with no limits. Folders are flattened, so duplicate basenames collide.
7. Image types are matched by prefix, so `ProjectLogo` becomes a gallery image.
8. Every image is re-encoded into fixed boxes.
9. Files written before a failed DB insert become orphans.
10. Job state is in memory and lost on restart.
11. File checks are by extension only.
12. Tests: the existing `@SpringBootTest` tests need a live MySQL. There is no H2 or Testcontainers dependency.
13. Security observation (not changed): `application-dev.properties` contains committed credentials (DB, JWT secret, mail). Rotate them and move them to environment variables.

---

## 8. Existing-code dependencies that block a pure zero-modification implementation

| # | Blocker | Why | Zero-mod alternative |
|---|---|---|---|
| B1 | Draft-only creation | `status=true` is hard-coded in a private method | **Persistence guard** (D1-A) or a **parallel draft importer** (D1-B) |
| B2 | Never update existing projects | update-on-slug-match is hard-coded | Pre-validate and skip duplicate rows **plus** the persistence guard vetoes any write to a pre-existing project (closes the race window) |
| B3 | Images must attach only to batch projects | ZIP key resolves against all projects | Pre-validate every key against the batch's predicted slugs, and send the importer only images for projects created in this job |
| B4 | DB-backed integration tests | no embedded DB in `pom.xml` | Unit tests with Mockito (no change) + integration tests against a disposable local MySQL schema, **or** approve adding `h2` (test scope) to `pom.xml` |
| B5 | Narrower-than-MANAGE_PROJECTS permission | keys are a closed list in `AdminPermissionKeys` | Endpoint-level email allow-list in the new module |
| B6 | Pilot v4 template headers | file not available | Owner supplies the workbook. Until then the template endpoint serves the importer's real headers (section 1). |

No existing file needs to change if D1-A or D1-B is approved.

---

## 9. Proposed integration architecture (Phase 2). Superseded by section 0

> Historical proposal. The implemented design uses the separate draft importer (D1-B), not the
> Hibernate guard or the call into `uploadProjectsExcel` described below. The current file list is
> in `CLAUDE_INTEGRATION_DEPLOYMENT.md`.

Package: `com.mypropertyfact.estate.integration.claude`. It must sit under `com.mypropertyfact.estate` so the existing `@SpringBootApplication` component and entity scan picks it up without a config change. The brief's `com.mypropertyfact.integration.claude` is outside the scan.

```
ClaudeIntegrationController  (/api/v1/integrations/claude, @PreAuthorize MANAGE_PROJECTS + allow-list)
   │
   ├─ ClaudeBatchValidator (dry-run; never writes)
   │     ├─ ClaudeExcelValidator   → exact importer header names, required fields, unknown headers = error,
   │     │                           amenities/location-advantage parse check, master-data existence,
   │     │                           duplicate rows inside the workbook
   │     ├─ ClaudeDuplicateDetector → slug, RERA no, name+builder+city vs existing projects
   │     └─ ClaudeZipValidator      → size/entry/ratio limits, path traversal, duplicate basenames,
   │                                  magic-byte + ImageIO decode, dimensions, key→row mapping,
   │                                  type recognised (Logo/Thumbnail/Location/Desktop*/Mobile*/Gallery*)
   │
   ├─ ClaudeJobService  → persists ClaudeIntegrationJob + ClaudeIntegrationJobItem (per row),
   │                      idempotency key = SHA-256(excel)+SHA-256(zip) (unique index)
   │
   └─ ClaudeImportExecutor (own single-thread executor; doesn't compete with the admin import queue)
         └─ ClaudeExistingImporterAdapter
               1. builds a filtered workbook: header + only the rows that passed validation
               2. builds a filtered ZIP: only images whose key maps to those rows
               3. opens ClaudeDraftGuard scope (thread-local) and calls the EXISTING
                  ProjectExcelUploadService.uploadProjectsExcel(excel, zip) on this thread
               4. closes scope → ClaudeImportVerificationService re-reads each project by slug:
                  exists, id, status == false, fields match, image counts → per-row result
```

### Draft enforcement and overwrite protection (recommended D1-A): `ClaudeDraftGuard`

This is a new `@Component`. At startup it registers Hibernate `PreInsert`, `PostInsert`, `PreUpdate`, `PreDelete` and collection listeners via `EventListenerRegistry`. It's a new file: no entity, config or existing class changes. **Outside a Claude import thread every listener returns immediately.** Inside the scope:

- `PreInsert` of a `Project` forces `status=false` (entity field and insert state array) **before the SQL INSERT**. The row is never published, not even briefly.
- `PostInsert` of a `Project` records its id as "created by this job".
- Any `UPDATE` or `DELETE` of a `Project`, any write to a project-owned row (gallery, banners, FAQs, floor plans, location benefits, about, walkthrough, amenity links), or any status flip to `true` whose project id is **not** in the job's created set throws. The existing per-row `REQUIRES_NEW` transaction then rolls back that row only. The row is reported as `DUPLICATE_REVIEW_REQUIRED`.

This lets the existing importer run unchanged while guaranteeing at the database layer that (a) every new project is a draft and (b) no existing project is touched, even if another admin creates the same slug between validation and import.

Trade-offs: it relies on Hibernate's event SPI (stable in Hibernate 6). It is process-wide, but a no-op unless the thread-local is set.

**D1-B alternative:** `ClaudeDraftProjectImporter` re-implements the ~300 lines of row mapping (same headers, parsers and slug rules), creates drafts, and never updates. It has no global hook, but **duplicates business logic that will drift** whenever the existing importer changes. Images would still go through the existing `uploadProjectsExcel` (header-only workbook + ZIP).

### New APIs (prefix `/api/v1/integrations/claude`)

| API | Behaviour |
|---|---|
| `POST /properties/validate` | multipart `excelFile`, `imagesZip`. Full report. Writes nothing (does not even create a job row). |
| `POST /properties/import` | multipart `excelFile`, `imagesZip`, `batchName?`, `dryRun?`. Validates synchronously. If there are blocking errors → 422 + report. If the idempotency key was already seen → 409 + existing jobId. Otherwise → 202 `{jobId,status:QUEUED}`. |
| `GET /jobs/{jobId}` | status, counts, timestamps, error summary |
| `GET /jobs/{jobId}/results` | per row: excel row, name, builder, city, RERA, projectId, slug, draft (status==false), imagesImported, itemStatus (`CREATED_DRAFT`, `FAILED`, `DUPLICATE_REVIEW_REQUIRED`, `SKIPPED_VALIDATION`), errors[] |
| `GET /template` | `.xlsx` with the importer's real headers + "Image names" sheet; `?format=json` returns headers + naming rules |

Job statuses: `RECEIVED, VALIDATING, VALIDATION_FAILED, QUEUED, PROCESSING, PARTIALLY_COMPLETED, COMPLETED, FAILED`.

### Authentication

The existing login (`/api/v1/admin-portal/auth/login` with `dashboardUsername`) returns a JWT that Claude then sends as a Bearer token. No new auth mechanism is needed. Layered checks on every endpoint:

1. `@PreAuthorize MANAGE_PROJECTS`
2. `ClaudeIntegrationAccessService`: principal email ∈ `claude.integration.allowed-emails`, and the module is enabled (`claude.integration.enabled`, default **false**)
3. Per-user rate limit (in-memory token bucket)

Settings bind from **environment variables** (`CLAUDE_INTEGRATION_ENABLED`, `CLAUDE_INTEGRATION_ALLOWED_EMAILS`, …) with safe defaults in code, so **no existing `application*.properties` file needs editing**.

### Database (new tables only)

- `claude_integration_job`: id, job_id (uuid, unique), batch_name, idempotency_key (unique), submitted_by_user_id, submitted_by_email, original_excel_filename, original_zip_filename, excel_sha256, zip_sha256, total/successful/failed/pending/duplicate counts, job_status, error_summary (TEXT), created_at, started_at, completed_at
- `claude_integration_job_item`: id, job_id FK, excel_row, project_name, builder_name, city_name, rera_no, predicted_slug, project_id (nullable, no FK to keep `projects` untouched), item_status, images_expected, images_imported, is_draft, errors (TEXT), created_at

Both are created by `ddl-auto=update` and also shipped as `migrations/add_claude_integration_tables.sql` for manual or production use. **No constraint or index is added to existing tables.**

### Files to be created (all new)

```
src/main/java/com/mypropertyfact/estate/integration/claude/
  config/ClaudeIntegrationProperties.java, ClaudeIntegrationExecutorConfig.java
  controller/ClaudeIntegrationController.java
  dto/ClaudeImportResponse.java, ClaudeJobResponse.java, ClaudePropertyResult.java,
      ClaudeValidationReport.java, ClaudeValidationIssue.java, ClaudeTemplateResponse.java
  entity/ClaudeIntegrationJob.java, ClaudeIntegrationJobItem.java, ClaudeJobStatus.java, ClaudeItemStatus.java
  repository/ClaudeIntegrationJobRepository.java, ClaudeIntegrationJobItemRepository.java,
             ClaudeProjectLookupRepository.java   (read-only queries on Project: slug / RERA / name+builder+city)
  security/ClaudeIntegrationAccessService.java, ClaudeRateLimiter.java
  guard/ClaudeDraftGuard.java, ClaudeImportScope.java          (D1-A)
  validator/ClaudeExcelValidator.java, ClaudeZipValidator.java, ClaudeImageValidator.java,
            ClaudeDuplicateDetector.java, ClaudeImporterSpec.java  (header list + image-type rules mirrored from source)
  service/ClaudeIntegrationService.java, ClaudeJobService.java, ClaudeImportVerificationService.java,
          ClaudeExistingImporterAdapter.java
  processor/ClaudeWorkbookFilter.java, ClaudeZipFilter.java
  storage/ClaudeInMemoryMultipartFile.java, ClaudeTemporaryStorageService.java
  exception/ClaudeIntegrationException.java, ClaudeIntegrationExceptionHandler.java (scoped to this package)
src/test/java/com/mypropertyfact/estate/integration/claude/...   (new tests)
migrations/add_claude_integration_tables.sql
CLAUDE_INTEGRATION_API.md, CLAUDE_PROPERTY_WORKFLOW.md, CLAUDE_IMAGE_NAMING_SPEC.md,
CLAUDE_INTEGRATION_TEST_REPORT.md, CLAUDE_INTEGRATION_DEPLOYMENT.md
```

### Transactions and failure handling (honest limits)

- Each project row commits independently (the existing behaviour). The guard rolls back a row that touches a pre-existing project.
- Images for a project commit in one transaction per project. If that fails, files already written stay on disk under the new draft's own folder (existing behaviour). Verification marks the row `CREATED_DRAFT` with `imagesImported < imagesExpected` and an error. **There is no MySQL + filesystem atomicity.**
- Nothing is ever deleted as compensation.
- Concurrency: one Claude job runs at a time (single-thread executor). The unique idempotency index stops double submission across instances. The guard covers races with human admins.

---

## 10. Validation rules the new module will add on top of the importer (Claude path only)

These are stricter than the existing importer, and they apply only to the Claude endpoints:

- Every header must be a known importer header. Unknown headers are an error, not silently dropped.
- Required: PROJECT NAME, BUILDER NAME, CITY NAME, STATE NAME, PROPERTY TYPE NAME, PROJECT STATUS NAME.
- Status, type, city, state and country must already exist. No auto-create and no "first row" fallback (D2).
- Every `PROJECT AMENITIES` entry must match an existing amenity. Every `LOCATION ADVANTAGE` segment must parse.
- Duplicate slugs inside the workbook are an error.
- An existing slug, RERA number, or name+builder+city match means `DUPLICATE_REVIEW_REQUIRED`, and the row is skipped.
- ZIP limits: ≤ 300 MB compressed, ≤ 1 GB extracted, ≤ 2000 entries, ratio ≤ 100:1, no `..`/absolute paths, no duplicate basenames, images only (magic bytes + decode), at most 1 logo, thumbnail and location per project.
- Image type must be one of `Logo`, `Thumbnail`, `Location`, `Desktop*`, `Mobile*`, `Gallery*`. Anything else (e.g. `ProjectLogo`) is an **error**, not a silent gallery image.
- Every image key must map to a row in this workbook. Every row should have Desktop, Mobile and Thumbnail images (warning by default).

---

## 11. Decisions needed before Phase 3

- **D1: Draft enforcement and overwrite protection.** A (recommended): reuse the existing importer unchanged + `ClaudeDraftGuard` Hibernate listener. B: parallel draft importer that duplicates the row mapping. C: approve a small edit to `ProjectExcelUploadService` (breaks zero-mod).
- **D2: Master data.** Recommended: block rows that would create a new city, state or country, or that use an unknown status or type. Allow a new builder with a warning. (Alternative: block new builders too.)
- **D3: Image sizes.** Reusing the importer means the existing resize boxes apply (2508×1200, 800², 792×203, 1200×800, 1600×1200). Recommended: accept this, and have Image Studio produce images at those sizes (or larger, same aspect). The alternative is a separate image path, which is a second importer.
- **D4: Template.** Please provide `Property_List_SEO_Pilot_v4.xlsx`. Its builder-description column has no importer target. Options: drop it, or keep it only in the research report.
- **D5: Tests.** Recommended: Mockito unit tests + an opt-in integration suite against a disposable local MySQL schema (no `pom.xml` change). Alternative: approve adding `com.h2database:h2` (test scope) to `pom.xml`.
