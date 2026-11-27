# Claude Integration: Test Report

Date: 2026-11-23 · Runtime: Temurin JDK 21.0 (Maven offline, Spring Boot 3.3.5) · DB for integration tests: local MariaDB 10.4.32, **disposable schema `mpf_claude_it`** (created on demand, rebuilt by `ddl-auto=create-drop`).

**Result: 60/60 connector tests pass. The 7 existing tests also pass with the connector loaded.**

## How to run

```bash
# Unit tests (no database). Use JDK 17 or 21: Mockito/ByteBuddy 1.14 in this Spring Boot version cannot mock on JDK 25.
JAVA_HOME=<jdk-21> ./mvnw test -Dtest='Claude*Test'

# Plus database integration tests (disposable schema only; the test refuses any other schema)
CLAUDE_IT=true JAVA_HOME=<jdk-21> ./mvnw test -Dtest='Claude*Test'
```

`ClaudeIntegrationDbTest` is skipped unless `CLAUDE_IT=true`. It overrides the datasource URL to `…/mpf_claude_it` and aborts if the connected catalog is anything else. It never touches `mypropertyfact`.

## Results by class

| Class | Tests | Pass | Kind |
|---|---|---|---|
| `ClaudeImporterSpecTest` | 10 | 10 | unit |
| `ClaudeWorkbookParserTest` | 5 | 5 | unit (uses the real reference workbook) |
| `ClaudeBatchValidatorTest` | 13 | 13 | unit + Mockito (real workbook vs. dev-DB master data) |
| `ClaudeZipInspectorTest` | 9 | 9 | unit |
| `ClaudeSecurityUnitTest` | 8 | 8 | unit + Mockito |
| `ClaudeTemplateServiceTest` | 2 | 2 | unit |
| `ClaudeIntegrationDbTest` | 13 | 13 | Spring Boot + MockMvc + MySQL disposable schema |
| **Total** | **60** | **60** | |

## Requirement → test evidence

| Requirement | Test(s) |
|---|---|
| Exact workbook header mapping (32 headers, A–AF, AG ignored, Sheet1, rows 2–21) | `ClaudeWorkbookParserTest.readsTheReferenceWorkbookExactly`, `ClaudeBatchValidatorTest.referenceWorkbookAgainstDevMasterData`, `ClaudeTemplateServiceTest` |
| Leading/trailing whitespace and case normalisation (` PROJECT CONFIGURATION`, `PROJECT Amenities`, lookups) | `ClaudeImporterSpecTest.headerNormalisation…`, `ClaudeBatchValidatorTest.lookupsAreCaseAndWhitespaceInsensitive` |
| All four FAQ pairs | `ClaudeImporterSpecTest.allFourFaqPairsAreRecognised`; 4 FAQs per row in the reference test; DB check `project_faqs = 4` in the happy path |
| `ID` is a reference only | `ClaudeImporterSpecTest.idIsReferenceOnly…`, `sourceReference` assertions |
| BUILDER DESCRIPTION handling | `ClaudeBatchValidatorTest.referenceWorkbookAgainstDevMasterData` (7 existing builders → not applied, kept), `newBuilderGetsDescriptionOnlyFromItsFirstRow`; DB: new builder gets the description, existing builder row byte-identical |
| Unknown city/state/country | `ClaudeBatchValidatorTest.unknownCityState…`, DB `unknownMasterDataBlocksAndCreatesNothing` (no city or builder rows created) |
| Unknown status/type, no fallback | same unit test + reference row 5 (`New Launch`) |
| Unknown amenities never dropped | `unknownAmenityIsAnErrorAndNeverSilentlyDropped`; reference: `Rainwater-Harvesting` in all 20 rows |
| New builder creation / existing builder protection | DB `importCreatesVerifiedDrafts…` (`builderCreated`, `snapshotBuilder` before = after) |
| Duplicate detection (slug, RERA, name+builder+city) | `ClaudeBatchValidatorTest.existingProjectsBySlugReraAndNameBuilderCityAreDuplicates`, `duplicatesInsideTheWorkbookAreErrors`; DB `duplicatesAreNeverWrittenAndPartialBatchesComplete` (published, draft and custom-slug projects) |
| Draft-only creation, **at insertion** | DB happy path: a test-only `AFTER INSERT/UPDATE` trigger on `projects` records `status` on every write. Asserts the INSERT had `status = 0` and no later write ever had `status ≠ 0`. |
| Existing published and draft projects unchanged | DB snapshots of the full `projects` row + 9 child-table counts + FAQ text, before and after imports, in the happy-path, duplicate and failed-row tests |
| Failed row does not modify existing project; full rollback | DB `failedRowRollsBackAndNeverModifiesExistingProjects` (duplicate inside the transaction; FK failure after creating a new builder → builder, claims and project all rolled back) |
| Concurrency | DB `concurrentImportsOfTheSameProjectCreateExactlyOne` (two threads, same project → exactly 1 row) |
| Image-to-new-project association | DB happy path (5 images per project: gallery/desktop/mobile rows + logo/thumbnail columns + 5 files under `properties/<slug>/`); `imagesCannotBeAttachedToAProjectThisJobDidNotCreate` |
| Invalid ZIP and image files | `ClaudeZipInspectorTest` (ZIP Slip, duplicate names, non-image, fake image, extension mismatch, truncated JPEG, too small, archive bomb by ratio and by size, entry limit, unreadable archive); DB `invalidZipContentIsRefused` |
| Image naming (folder mismatch, unknown type `ProjectLogo`, project not in workbook, second logo) | `ClaudeBatchValidatorTest.imagesMapToTheirRow…`, `secondLogoAndMissingCoreImagesAreReported` |
| Job failure and partial completion | DB `jobWhoseFilesVanishFailsCleanlyAndCanBeRetried` (`FAILED`, idempotency released, retry succeeds), `duplicatesAreNeverWritten…` (`PARTIALLY_COMPLETED`) |
| Restart survival | DB `interruptedJobsAreClosedOnRestart`; jobs and results are read back from the database throughout |
| Idempotency | DB happy path: same files again → `409` with the original `jobId` |
| Validate writes nothing | DB `validateEndpointWritesNothing` (project, builder and job counts unchanged) |
| Auth: valid account, invalid credentials, expired JWT, no MANAGE_PROJECTS, not allow-listed, unauthenticated | DB `authenticationAndAuthorization` (real login endpoint + BCrypt + JWT filter), `ClaudeSecurityUnitTest` |
| Least privilege (SUPERADMIN, empty permissions = legacy full access, extra permissions) | `ClaudeSecurityUnitTest.overPrivilegedAccountsAreRefused` |
| Integration account blocked from existing write APIs | DB `integrationAccountIsBlockedFromExistingWriteApis` (DELETE project → 403, existing Excel upload → 403, admin GET → 200, project unchanged), unit filter test |
| Not publicly accessible | DB happy path: `GET /api/v1/projects/get/{slug}` ≠ 200; admin GET-by-id = 200 |

## Regression evidence for the existing system

1. **No tracked file changed.** `git status --porcelain` in `mpf-backend` lists only untracked new files (see `CLAUDE_INTEGRATION_DEPLOYMENT.md` §1). `git diff --stat HEAD` is empty.
2. **Existing Excel importer still works unchanged** (`ClaudeIntegrationDbTest.existingExcelImporterStillWorksUnchanged`). `POST /api/v1/excel-upload/projects` as a normal staff admin returns `Created: 1`. The project is published (`status = 1`), which is the existing behaviour, deliberately left as it is. A second upload via the service returns `Updated: 1`, also the existing behaviour.
3. **Existing test suite passes** with the connector loaded: `AmenitiesTests` (1), `ProjectTests` (1), `MyPropertyFactApplicationTests` (5) → 7/7. Run against the disposable schema with `-Dspring.datasource.url=…/mpf_claude_it -Dspring.jpa.hibernate.ddl-auto=update`, so the dev database was not used.
4. The connector's servlet filter only acts on allow-listed integration accounts. Unit and DB tests confirm that other accounts (and anonymous requests) pass through.

## Failures found and fixed during testing (new code only)

| Failure | Cause | Fix |
|---|---|---|
| Context failed: `No default constructor found` for `ClaudeRateLimiter` | two constructors | `@Autowired` on the public constructor |
| `409` expected, got `500` on a resubmitted batch | duplicate lookup ran inside the already-failed transaction; every integrity error was treated as a duplicate | separate read-only check before and after the insert; only a real idempotency-key hit returns 409 |
| Test-only issues | immutable map passed to `JwtService` (which adds claims); job id longer than 36 chars; leftover upload files from an earlier run | fixed in the test |

## Pre-existing issues observed (not changed)

| Issue | Impact | Handling |
|---|---|---|
| `BlogStatusCheckConstraintRunner` fails on a **fresh** database (`ALTER TABLE blogs DROP CHECK` throws on MariaDB, and the caught exception still leaves the transaction rollback-only) | Application cannot start against an empty schema, for example a brand-new staging DB | DB tests use `@MockBean` for that runner in their own context only. The existing-test regression run used the disposable schema after setting `blogs_chk_1` to the value the runner expects. Fix belongs to the owners of that file (approval needed). |
| Mockito cannot mock on JDK 25 with ByteBuddy 1.14 | tests must run on JDK 17/21 (production image uses 17) | documented |
| Dialect warning: MariaDB 10.4 reported as "5.5.5", below Hibernate's minimum 8.0 | none observed | none |

## Not covered by automated tests

- A run against the real dev or staging data. Validation of the reference workbook was done in unit tests against master data copied by read-only `SELECT`s from the local dev DB.
- Multi-instance concurrency across two JVMs. The guarantee comes from the database unique index, exercised by two threads in one JVM.
- HTTPS and reverse-proxy upload limits (deployment concern, see `CLAUDE_INTEGRATION_DEPLOYMENT.md`).
