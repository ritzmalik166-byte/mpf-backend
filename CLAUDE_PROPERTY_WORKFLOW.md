# Claude Property Workflow: Research to Verified Draft

This workflow runs **outside** the Spring Boot application (a Claude agent with web access, a spreadsheet/ZIP toolchain and HTTPS access to MPF). It works for one property or for a batch. The backend contract is in `CLAUDE_INTEGRATION_API.md`, and image rules are in `CLAUDE_IMAGE_NAMING_SPEC.md`.

**Non-negotiable:** the workflow only ever creates **drafts**. It never publishes, never edits existing projects, and never reports success until the job results show `CREATED_DRAFT`, `draft: true` and `verified: true` for each property.

---

## 1. Input

```json
{
  "batchName": "November_Property_Batch",
  "properties": [
    {"projectName": "Victory Ace", "builder": "Victory Infraprojects", "city": "Noida", "hint": "Sector 143B"},
    {"projectName": "HI Castle", "builder": "HI Castle Realtech", "city": "Greater Noida"}
  ]
}
```

## 2. Research (per property)

Source priority, which must be recorded for every fact:

1. Official RERA portal (UP-RERA `up-rera.in`, HARERA `haryanarera.gov.in`, MahaRERA, …)
2. Official builder or project website
3. Official brochure (PDF from the builder)
4. Reliable third-party portals: for **cross-checking only**, never as the sole source for RERA, price, possession or approvals

Classify every value as one of `VERIFIED_OFFICIAL`, `OFFICIAL_UNVERIFIED`, `THIRD_PARTY`, `AI_GENERATED` (descriptions, FAQs) or `UNKNOWN`.

**Never invent:** RERA numbers, possession dates, prices, builder details, unit configurations, amenities, approvals, or distances. If a value is unknown, leave the cell blank. The importer never overwrites anything, so blanks are safe.

### RERA verification

- Search the state portal by project name or promoter, and record the exact registration number as shown, the registration date, the promoter name and the source URL.
- If the portal is unreachable or the project is not found, leave `RERA NO` blank and flag the property as `NEEDS_HUMAN_REVIEW` in the research report. Never use a number from a portal listing alone.
- One RERA number may cover a whole township. If the backend reports `DUPLICATE_REVIEW_REQUIRED` by RERA (for example the reference workbook's row 4, `95 OF 2017`), stop for that property and hand it to a human.

### Research report (kept outside the Excel)

`research/<batchName>/report.json`, one entry per property:

```json
{
  "projectName": "Victory Ace",
  "facts": [
    {"field": "RERA NO", "value": "UPRERAPRJ332663", "class": "VERIFIED_OFFICIAL", "source": "https://www.up-rera.in/…", "checkedAt": "2026-11-23"},
    {"field": "PROJECT PRICE", "value": "1.7", "class": "THIRD_PARTY", "source": "https://…", "checkedAt": "2026-11-23", "note": "unit: crore"}
  ],
  "images": [{"file": "Victory-Ace_Desktop-1_My-Property-Fact.jpg", "origin": "AI_GENERATED_ILLUSTRATION", "prompt": "…"}],
  "status": "READY | NEEDS_HUMAN_REVIEW | BLOCKED"
}
```

Do not add extra columns to the workbook for provenance. Unknown populated columns are rejected by the backend.

## 3. Master data alignment (before building the workbook)

The backend does **not** create cities, states, countries, statuses, property types or amenities, and has no fallback. Use the exact existing values. Case, spacing and hyphens don't matter, but spelling does.

- Statuses: `Completed`, `Ready To Move`, `New Launched`, `Under Construction`, `Ultra Luxury`, `Featured`. **Not** "New Launch".
- Types: `Residential`, `Commercial`, `New Launches`
- Amenities: must match the master list, for example **`Rain-Water-Harvesting`**, not "Rainwater-Harvesting". When unsure, run `/properties/validate`; it suggests the closest title.
- City must belong to the given state, for example `Gurugram` → `Haryana` and `Noida Extension` → `Uttar Pradesh`.
- Builders may be new. A new builder is created with name, slug and `BUILDER DESCRIPTION`. For an existing builder the description is **not** written.

## 4. Content preparation (SEO and copy)

Per property, all marked `AI_GENERATED` in the report and grounded only in researched facts:

- `META TITLE` (≤ 255 chars; aim for 50–60), `META DESCRIPTION` (150–160 chars), `META KEYWORD` (comma-separated)
- `AMENITY DESCRIPTION`, `LOCATION DESCRIPTION`, `FLOOR PLAN DESCRIPTION`, `PROJECT WALKTHROUGH DESCRIPTION`, `BUILDER DESCRIPTION`
- `PROJECT Amenities`: `_`-separated master titles with `-` for spaces: `Swimming-Pool_Gym_Wi-Fi-Connectivity`
- `LOCATION ADVANTAGE`: **must** use `Place-Name-4.2-Km` segments separated by `_`, or `Place Name (4.2 Km)` separated by `|`. Only verified distances. Prose is not stored (it is reported as `LOCATION_ADVANTAGE_UNPARSED`).
- `PROJECT CONFIGURATION`: `2 BHK-1183 sq.ft, 3 BHK-1511 sq.ft` (type, `-`, area). Spell types correctly ("Studio", not "Studo").
- Four FAQ pairs (`Question 1`…`Answer 4`). Both halves are required, and answers must not contradict the researched facts.
- Check for copy/paste: each project's texts must mention only that project. The backend warns `DUPLICATE_TEXT_ACROSS_ROWS` / `TEXT_MENTIONS_OTHER_PROJECT`.

## 5. Excel generation

Use `GET /api/v1/integrations/claude/template` (or the reference workbook). Rules:

- Sheet `Sheet1`, headers in row 1, one property per row starting at row 2.
- The 32 headers, A–AF: `ID, BUILDER NAME, PROJECT NAME, PROPERTY TYPE NAME, PROJECT STATUS NAME, PROJECT CONFIGURATION, PROJECT LOCALITY, CITY NAME, STATE NAME, COUNTRY NAME, RERA NO, RERA WEBSITE, IVR NO, PROJECT PRICE, META TITLE, META DESCRIPTION, META KEYWORD, AMENITY DESCRIPTION, LOCATION DESCRIPTION, FLOOR PLAN DESCRIPTION, BUILDER DESCRIPTION, PROJECT Amenities, LOCATION ADVANTAGE, PROJECT WALKTHROUGH DESCRIPTION, Question 1, Answer 1, … Question 4, Answer 4`.
- `ID` is just a serial (1, 2, 3…). It is never used to find a project.
- Required per row: BUILDER NAME, PROJECT NAME, PROPERTY TYPE NAME, PROJECT STATUS NAME, CITY NAME, STATE NAME, COUNTRY NAME.
- Unknown values stay blank. No extra columns. No two rows for the same project.

## 6. Images

Allowed origins, recorded per file in the research report:

| Origin | Allowed | Notes |
|---|---|---|
| Official builder media with permission | yes | keep the permission evidence |
| Licensed stock | yes | keep the licence reference |
| AI-generated illustration | yes | must be an illustration (no fake "actual photo" claims), and must not imitate a portal's photos |
| Images copied from Magicbricks, 99acres, Housing or similar | **no** | |

If image rights cannot be established for a property, **do not import its images**. Either submit the property without images (the backend warns `NO_IMAGES`) or mark it `BLOCKED`.

Produce at least `Desktop-1` (≥ 2508×1200, ratio about 2.09:1), `Mobile-1` (≥ 800×800, square) and `Thumbnail` (≥ 800×800, square). Optional: `Logo` (792×203 PNG), `Location` (1200×800), `Gallery-1…n` (≥ 1600×1200). The backend resizes and re-encodes into these boxes.

## 7. ZIP generation

One ZIP per batch, one folder per project, named as in `CLAUDE_IMAGE_NAMING_SPEC.md`:

```
properties_images.zip
  Victory-Ace/Victory-Ace_Desktop-1_My-Property-Fact.jpg
  Victory-Ace/Victory-Ace_Mobile-1_My-Property-Fact.jpg
  Victory-Ace/Victory-Ace_Thumbnail_My-Property-Fact.jpg
  HI-Castle/HI-Castle_Desktop-1_My-Property-Fact.jpg …
```

Pre-flight checks before upload: every row has a folder, every folder matches a row, file names are unique, there are no other file types, and the total size is ≤ 300 MB (split larger batches).

## 8. Authenticate

```
POST /api/v1/admin-portal/auth/login  {email, password, dashboardUsername}  → token
```

Credentials come from the agent's secret store (environment variables). Never put them in prompts, logs, reports or files. Keep the token in memory only, and log in again when it expires.

## 9. Validate (mandatory)

`POST /properties/validate` with the workbook and ZIP.

- Stop if `workbookIssues` or `zip.issues` contain any `ERROR`.
- For each row with status `BLOCKED`, fix the data (from sources, never by guessing) and validate again.
- For each row with status `DUPLICATE_REVIEW_REQUIRED`, remove it from the batch and list it in the report with `existingProjectId`. Never rename a project to get past the duplicate check.
- Read every warning. `BUILDER_DESCRIPTION_NOT_APPLIED`, `LOCATION_ADVANTAGE_UNPARSED`, `IMAGE_*` and `TEXT_MENTIONS_OTHER_PROJECT` need either a fix or a note in the final report.

Proceed only when `valid: true`, or when you have intentionally decided to import just the valid rows (`allowPartial=true`) and listed the rest.

## 10. Import, poll and verify

1. `POST /properties/import` (same files, `batchName`, optional `idempotencyKey` = batch id). Expect `202` and save the `jobId`. A `409` means the batch was already accepted: poll that `jobId` instead of resubmitting.
2. Poll `GET /jobs/{jobId}` every 5–10 s until `status` is `COMPLETED`, `PARTIALLY_COMPLETED`, `FAILED` or `VALIDATION_FAILED`. Give up after 30 minutes and report the job as stuck.
3. `GET /jobs/{jobId}/results`. For each row, a success means **all** of: `importStatus = CREATED_DRAFT`, `draft = true`, `verified = true`, `errors = []`, and `imagesImported = imagesExpected`.
4. Optional extra check: `GET /api/v1/projects/admin/get-by-id/{projectId}` returns the draft. The public `GET /api/v1/projects/get/{slug}` must **not** return it.

**Never report success because an HTTP call returned 2xx.** Only the job results count.

## 11. Final batch report

```markdown
# Batch November_Property_Batch: job 7f0c…: PARTIALLY_COMPLETED

## Created drafts (verified)
| Row | Project | Project ID | Slug | Images | Builder |
| 2 | The Core Ultra Wide | 1402 | the-core-ultra-wide | 7/7 | new (#512) |

## Needs review
| Row | Project | Status | Reason | Existing ID |
| 4 | Central Park Clover Floors | DUPLICATE_REVIEW_REQUIRED | RERA 95 OF 2017 matches project #1373 | 1373 |

## Not stored (kept for humans)
- Row 3, BUILDER DESCRIPTION: builder "METRO SUITES HOMES LLP" exists; text kept: "…"

## Sources
research/November_Property_Batch/report.json (verification dates 2026-11-23)
```

## 12. Failure handling

| Situation | Action |
|---|---|
| 401 | Log in again once, then retry. If it fails again, stop. |
| 403 `ACCOUNT_TOO_PRIVILEGED` / `INTEGRATION_ACCOUNT_RESTRICTED` | Stop. This is an account configuration problem for a Super Admin. |
| 422 | Fix it from the report and validate again. Never loop more than 3 times without human input. |
| 429 | Wait 60 s. |
| `FAILED` / `PARTIALLY_COMPLETED` | Report each row's status. Do not resubmit created rows (they become duplicates). Resubmit only fixed rows in a new workbook. |
| Anything unexpected | Stop and report. Never fall back to the admin Excel upload: it publishes immediately. |

## 13. Using the reference workbook as-is

Validating `Property List-1 - Copy (1).xlsx` against current master data gives **0 importable rows**. Before submitting:

1. In every row, replace `Rainwater-Harvesting` with `Rain-Water-Harvesting`.
2. In row 5, change `New Launch` to `New Launched` (or ask a Super Admin to add the status).
3. Rows 20 and 21: rewrite `LOCATION ADVANTAGE`. It currently describes Raheja Maheshwara.
4. All rows: convert `LOCATION ADVANTAGE` into `Place-Name-X-Km` segments with verified distances, or accept that no location benefits are stored.
5. Row 4: remove it, or hand it to a human (its RERA number belongs to existing project #1373).

After steps 1–2 the validator reports 19 importable rows and 1 duplicate.
