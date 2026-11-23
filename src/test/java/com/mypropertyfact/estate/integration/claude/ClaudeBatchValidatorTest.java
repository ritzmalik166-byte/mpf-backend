package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationReport;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationReport.RowReport;
import com.mypropertyfact.estate.integration.claude.model.ClaudeRowPlan;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeWorkbookParser;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeZipInspector;
import com.mypropertyfact.estate.integration.claude.repository.ClaudeProjectClaimRepository;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService.MasterData;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService.Ref;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import com.mypropertyfact.estate.integration.claude.validator.ClaudeBatchValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeBatchValidatorTest {

    private ClaudeLookupService lookup;
    private ClaudeProjectClaimRepository claims;
    private ClaudeIntegrationProperties props;
    private ClaudeBatchValidator validator;
    private MasterData md;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        lookup = mock(ClaudeLookupService.class);
        claims = mock(ClaudeProjectClaimRepository.class);
        props = new ClaudeIntegrationProperties();
        md = ClaudeTestFixtures.devLikeMasterData();
        when(lookup.loadMasterData()).thenAnswer(i -> md);
        when(lookup.projectsBySlug(anyString())).thenReturn(List.of());
        when(lookup.projectsByReraKey(any())).thenReturn(List.of());
        when(lookup.projectsByNameBuilderCity(any(), any(), any())).thenReturn(List.of());
        when(claims.findByClaimKeyIn(anyCollection())).thenReturn(List.of());
        validator = new ClaudeBatchValidator(new ClaudeWorkbookParser(), new ClaudeZipInspector(props), lookup, claims, props, new FileUtils());
    }

    private static RowReport row(ClaudeValidationReport r, int excelRow) {
        return r.getRows().stream().filter(x -> x.getExcelRow() == excelRow).findFirst().orElseThrow();
    }

    private static Set<String> codes(List<ClaudeValidationIssue> issues) {
        return issues.stream().map(ClaudeValidationIssue::getCode).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ reference workbook

    @Test
    void referenceWorkbookAgainstDevMasterData() throws Exception {
        when(lookup.projectsByReraKey("95OF2017"))
                .thenReturn(List.of(ClaudeTestFixtures.project(1373, "Central Park Flower Valley Mikasa Plots", true, "95 OF 2017")));

        ClaudeBatchValidator.Result result = validator.validate(ClaudeTestFixtures.referenceWorkbook(),
                "Property List-1 - Copy (1).xlsx", null, null);
        ClaudeValidationReport r = result.report;

        // Headers: all 32 mapped, nothing unknown or missing.
        assertThat(r.getWorkbook().getDetectedHeaders()).hasSize(32);
        assertThat(r.getWorkbook().getUnknownHeaders()).isEmpty();
        assertThat(r.getWorkbook().getMissingRequiredHeaders()).isEmpty();
        assertThat(r.getWorkbookIssues()).noneMatch(ClaudeValidationIssue::isError);
        assertThat(r.getWorkbook().getDetectedHeaders().get(0).getStatus()).isEqualTo("REFERENCE_ONLY");
        assertThat(r.getWorkbook().getDetectedHeaders().get(20).getStatus()).isEqualTo("NEW_BUILDER_ONLY");
        assertThat(r.getWorkbook().getUnsupportedFields()).containsKeys("ID", "BUILDER DESCRIPTION", "LOCATION ADVANTAGE");
        assertThat(r.getTotalRows()).isEqualTo(20);

        // Every row lists "Rainwater-Harvesting"; the master title is "Rain Water Harvesting".
        for (RowReport rr : r.getRows()) {
            assertThat(rr.getErrors()).as("row " + rr.getExcelRow())
                    .anyMatch(i -> "UNKNOWN_AMENITY".equals(i.getCode()) && i.getMessage().contains("Rain Water Harvesting"));
            assertThat(rr.getAmenitiesMatched()).hasSize(36);
            assertThat(rr.getFaqs()).isEqualTo(4);
            assertThat(codes(rr.getWarnings())).contains("LOCATION_ADVANTAGE_UNPARSED", "NO_IMAGES");
            assertThat(rr.getUnappliedFields()).containsKey("LOCATION ADVANTAGE");
            assertThat(rr.getStatus()).isEqualTo("BLOCKED");
        }
        // Row 5: "New Launch" is not a status (master has "New Launched"); no fallback.
        assertThat(row(r, 5).getErrors()).anyMatch(i -> "UNKNOWN_PROJECT_STATUS".equals(i.getCode()));
        // Row 4: RERA "95 OF 2017" already belongs to project 1373.
        assertThat(row(r, 4).getExistingProjectId()).isEqualTo(1373);
        assertThat(row(r, 4).getDuplicateReasons()).anyMatch(s -> s.contains("RERA"));
        // Existing builders keep their profile; BUILDER DESCRIPTION is reported, not written.
        for (int excelRow : new int[]{3, 4, 10, 13, 15, 19, 20}) {
            RowReport rr = row(r, excelRow);
            assertThat(rr.isNewBuilder()).as("row " + excelRow).isFalse();
            assertThat(codes(rr.getWarnings())).contains("BUILDER_DESCRIPTION_NOT_APPLIED");
            assertThat(rr.getUnappliedFields()).containsKey("BUILDER DESCRIPTION");
        }
        // New builders are flagged.
        assertThat(row(r, 2).isNewBuilder()).isTrue();
        assertThat(codes(row(r, 2).getWarnings())).contains("NEW_BUILDER");
        assertThat(row(r, 2).getUnappliedFields()).doesNotContainKey("BUILDER DESCRIPTION");
        // Rows 20 and 21 reuse row 19's LOCATION ADVANTAGE text, which names "Raheja Maheshwara".
        assertThat(codes(row(r, 20).getWarnings())).contains("DUPLICATE_TEXT_ACROSS_ROWS", "TEXT_MENTIONS_OTHER_PROJECT");
        assertThat(codes(row(r, 21).getWarnings())).contains("DUPLICATE_TEXT_ACROSS_ROWS", "TEXT_MENTIONS_OTHER_PROJECT");
        // ID is a reference only.
        assertThat(row(r, 2).getSourceReference()).isEqualTo("1");

        assertThat(r.isValid()).isFalse();
        assertThat(r.getImportableRows()).isZero();
        assertThat(r.getBlockedRows()).isEqualTo(20);
    }

    @Test
    void referenceWorkbookBecomesImportableOnceMasterDataMatches() throws Exception {
        when(lookup.projectsByReraKey("95OF2017"))
                .thenReturn(List.of(ClaudeTestFixtures.project(1373, "Central Park Flower Valley Mikasa Plots", true, "95 OF 2017")));
        md.amenities.computeIfAbsent(ClaudeText.amenityKey("Rainwater Harvesting"), k -> new ArrayList<>()).add(new Ref(999, "Rainwater Harvesting"));
        ClaudeTestFixtures.add(md.statuses, new Ref(99, "New Launch"));

        ClaudeBatchValidator.Result result = validator.validate(ClaudeTestFixtures.referenceWorkbook(), "wb.xlsx", null, null);
        ClaudeValidationReport r = result.report;
        assertThat(r.getImportableRows()).isEqualTo(19);
        assertThat(r.getDuplicateRows()).isEqualTo(1);
        assertThat(row(r, 4).getStatus()).isEqualTo("DUPLICATE_REVIEW_REQUIRED");
        assertThat(result.importable()).hasSize(19);

        ClaudeRowPlan core = result.plans.get(0);
        assertThat(core.slug).isEqualTo("the-core-ultra-wide");
        assertThat(core.cityId).isEqualTo(15);
        assertThat(core.stateId).isEqualTo(1);
        assertThat(core.projectStatusId).isEqualTo(2);
        assertThat(core.projectTypeId).isEqualTo(1);
        assertThat(core.amenityIds).hasSize(37);
        assertThat(core.faqs).hasSize(4);
        assertThat(core.floorPlans).hasSize(1);
        assertThat(core.newBuilder).isTrue();
        assertThat(core.builderDescription).startsWith("Concept Capital Infra Projects");
        assertThat(core.projectFields).containsEntry("ivrNo", "8920024793").containsEntry("projectPrice", "0.75")
                .containsEntry("reraNo", "UPRERAPRJ9641");
        assertThat(core.claimKeys()).contains("SLUG:the-core-ultra-wide", "RERA:UPRERAPRJ9641");
        // Raheja Developers appears twice and already exists: same builder id for both rows.
        assertThat(result.plans.get(17).builderId).isEqualTo(65);
        assertThat(result.plans.get(18).builderId).isEqualTo(65);
    }

    // ------------------------------------------------------------------ master data rules

    private ClaudeValidationReport validateRows(List<Map<String, Object>> rows) throws Exception {
        return validator.validate(ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, rows), "t.xlsx", null, null).report;
    }

    @Test
    void unknownCityStateCountryStatusAndTypeBlockTheRowWithoutFallback() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Alpha Homes", "Raheja Developers", "Atlantis", "Haryana", "R-1");
        Map<String, Object> b = ClaudeTestFixtures.validRow("Beta Homes", "Raheja Developers", "Gurugram", "Narnia", "R-2");
        Map<String, Object> c = ClaudeTestFixtures.validRow("Gamma Homes", "Raheja Developers", "Gurugram", "Haryana", "R-3");
        c.put("COUNTRY NAME", "Freedonia");
        Map<String, Object> d = ClaudeTestFixtures.validRow("Delta Homes", "Raheja Developers", "Gurugram", "Haryana", "R-4");
        d.put("PROJECT STATUS NAME", "Almost Ready");
        Map<String, Object> e = ClaudeTestFixtures.validRow("Epsilon Homes", "Raheja Developers", "Gurugram", "Haryana", "R-5");
        e.put("PROPERTY TYPE NAME", "Castle");
        Map<String, Object> f = ClaudeTestFixtures.validRow("Zeta Homes", "Raheja Developers", "Gurugram", "Uttar Pradesh", "R-6");

        ClaudeValidationReport r = validateRows(List.of(a, b, c, d, e, f));
        assertThat(codes(row(r, 2).getErrors())).contains("UNKNOWN_CITY");
        assertThat(codes(row(r, 3).getErrors())).contains("UNKNOWN_STATE");
        assertThat(codes(row(r, 4).getErrors())).contains("UNKNOWN_COUNTRY");
        assertThat(codes(row(r, 5).getErrors())).contains("UNKNOWN_PROJECT_STATUS");
        assertThat(codes(row(r, 6).getErrors())).contains("UNKNOWN_PROPERTY_TYPE");
        assertThat(codes(row(r, 7).getErrors())).contains("CITY_STATE_MISMATCH");
        assertThat(r.getRows()).allMatch(x -> "BLOCKED".equals(x.getStatus()));
    }

    @Test
    void unknownAmenityIsAnErrorAndNeverSilentlyDropped() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Alpha Homes", "Raheja Developers", "Gurugram", "Haryana", "R-1");
        a.put("PROJECT Amenities", "Gym_Helipad_Swimming-Pool");
        ClaudeValidationReport r = validateRows(List.of(a));
        assertThat(row(r, 2).getErrors()).anyMatch(i -> "UNKNOWN_AMENITY".equals(i.getCode()) && "Helipad".equals(i.getValue()));
        assertThat(row(r, 2).getAmenitiesMatched()).containsExactly("Gym", "Swimming Pool");
    }

    @Test
    void lookupsAreCaseAndWhitespaceInsensitive() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Alpha Homes", "  raheja   developers ", " gurugram ", "HARYANA", "R-1");
        a.put("PROJECT STATUS NAME", "ready to move");
        a.put("PROPERTY TYPE NAME", " residential");
        a.put("COUNTRY NAME", "india ");
        ClaudeValidationReport r = validateRows(List.of(a));
        RowReport rr = row(r, 2);
        assertThat(rr.getErrors()).isEmpty();
        assertThat(rr.getStatus()).isEqualTo("VALID");
        assertThat(rr.getBuilderId()).isEqualTo(65);
    }

    @Test
    void newBuilderGetsDescriptionOnlyFromItsFirstRow() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Alpha Homes", "Brand New Builders", "Gurugram", "Haryana", "R-1");
        Map<String, Object> b = ClaudeTestFixtures.validRow("Beta Homes", "Brand New Builders", "Gurugram", "Haryana", "R-2");
        ClaudeBatchValidator.Result res = validator.validate(
                ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(a, b)), "t.xlsx", null, null);
        assertThat(res.plans.get(0).newBuilder).isTrue();
        assertThat(res.plans.get(0).builderDescription).isEqualTo("Brand New Builders is the developer of Alpha Homes.");
        assertThat(res.plans.get(1).newBuilder).isTrue();
        assertThat(res.plans.get(1).builderDescription).isNull();
        assertThat(res.plans.get(1).unappliedFields).containsKey("BUILDER DESCRIPTION");
    }

    // ------------------------------------------------------------------ structure

    @Test
    void unknownPopulatedHeaderBlocksTheBatch() throws Exception {
        List<String> headers = new ArrayList<>(ClaudeTestFixtures.HEADERS);
        headers.add("POSSESSION DATE");
        Map<String, Object> a = ClaudeTestFixtures.validRow("Alpha Homes", "Raheja Developers", "Gurugram", "Haryana", "R-1");
        a.put("POSSESSION DATE", "Dec 2027");
        ClaudeBatchValidator.Result res = validator.validate(ClaudeTestFixtures.workbook(headers, List.of(a)), "t.xlsx", null, null);
        assertThat(res.batchBlocked).isTrue();
        assertThat(res.report.getWorkbook().getUnknownHeaders()).containsExactly("POSSESSION DATE");
        assertThat(res.report.getWorkbookIssues()).anyMatch(i -> "EXCEL_UNKNOWN_HEADER".equals(i.getCode()));
    }

    @Test
    void missingRequiredHeaderBlocksTheBatch() throws Exception {
        List<String> headers = new ArrayList<>(ClaudeTestFixtures.HEADERS);
        headers.remove("CITY NAME");
        ClaudeBatchValidator.Result res = validator.validate(ClaudeTestFixtures.workbook(headers,
                List.of(ClaudeTestFixtures.validRow("Alpha Homes", "Raheja Developers", "Gurugram", "Haryana", "R-1"))), "t.xlsx", null, null);
        assertThat(res.batchBlocked).isTrue();
        assertThat(res.report.getWorkbook().getMissingRequiredHeaders()).containsExactly("CITY NAME");
    }

    @Test
    void requiredValuesTooLongValuesAndIncompleteFaqsAreRowErrors() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Alpha Homes", "Raheja Developers", "Gurugram", "Haryana", "R-1");
        a.remove("BUILDER NAME");
        a.put("META TITLE", "x".repeat(300));
        a.remove("Answer 3");
        ClaudeValidationReport r = validateRows(List.of(a));
        assertThat(codes(row(r, 2).getErrors())).contains("REQUIRED_VALUE_MISSING", "VALUE_TOO_LONG", "FAQ_INCOMPLETE");
    }

    @Test
    void duplicatesInsideTheWorkbookAreErrors() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Alpha Homes", "Raheja Developers", "Gurugram", "Haryana", "R-1");
        Map<String, Object> b = ClaudeTestFixtures.validRow("Alpha  Homes", "Raheja Developers", "Gurugram", "Haryana", "R-2");
        Map<String, Object> c = ClaudeTestFixtures.validRow("Gamma Homes", "Raheja Developers", "Gurugram", "Haryana", "r - 1");
        ClaudeValidationReport r = validateRows(List.of(a, b, c));
        assertThat(row(r, 2).getErrors()).isEmpty();
        assertThat(row(r, 3).getErrors()).anyMatch(i -> "DUPLICATE_IN_WORKBOOK".equals(i.getCode()));
        assertThat(row(r, 4).getErrors()).anyMatch(i -> "DUPLICATE_IN_WORKBOOK".equals(i.getCode()) && "RERA NO".equals(i.getColumn()));
    }

    @Test
    void existingProjectsBySlugReraAndNameBuilderCityAreDuplicates() throws Exception {
        when(lookup.projectsBySlug("alpha-homes")).thenReturn(List.of(ClaudeTestFixtures.project(11, "Alpha Homes", true, null)));
        when(lookup.projectsByReraKey("R-2")).thenReturn(List.of(ClaudeTestFixtures.project(12, "Old Beta", false, "R-2")));
        when(lookup.projectsByNameBuilderCity(eq("Gamma Homes"), eq(65), eq(17)))
                .thenReturn(List.of(ClaudeTestFixtures.project(13, "Gamma Homes", false, null)));
        ClaudeValidationReport r = validateRows(List.of(
                ClaudeTestFixtures.validRow("Alpha Homes", "Raheja Developers", "Gurugram", "Haryana", "R-1"),
                ClaudeTestFixtures.validRow("Beta Homes", "Raheja Developers", "Gurugram", "Haryana", "R-2"),
                ClaudeTestFixtures.validRow("Gamma Homes", "Raheja Developers", "Gurugram", "Haryana", "R-3"),
                ClaudeTestFixtures.validRow("Delta Homes", "Raheja Developers", "Gurugram", "Haryana", "R-4")));
        assertThat(row(r, 2).getStatus()).isEqualTo("DUPLICATE_REVIEW_REQUIRED");
        assertThat(row(r, 2).getExistingProjectId()).isEqualTo(11);
        assertThat(row(r, 2).getDuplicateReasons().get(0)).contains("published").contains("slug");
        assertThat(row(r, 3).getExistingProjectId()).isEqualTo(12);
        assertThat(row(r, 3).getDuplicateReasons().get(0)).contains("draft").contains("RERA");
        assertThat(row(r, 4).getExistingProjectId()).isEqualTo(13);
        assertThat(row(r, 5).getStatus()).isEqualTo("VALID");
        assertThat(r.getImportableRows()).isEqualTo(1);
        assertThat(r.isValid()).isFalse();
    }

    // ------------------------------------------------------------------ images

    @Test
    void imagesMapToTheirRowAndBadNamesAreReported() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Victory Ace", "Raheja Developers", "Gurugram", "Haryana", "R-1");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Victory-Ace/Victory-Ace_Desktop-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 2508, 1200));
        entries.put("Victory-Ace/Victory-Ace_Mobile-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 800, 800));
        entries.put("Victory-Ace/Victory-Ace_Thumbnail_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 800, 800));
        entries.put("Victory-Ace/Victory-Ace_Logo_My-Property-Fact.png", ClaudeTestFixtures.image("png", 792, 203));
        entries.put("Victory-Ace/Victory-Ace_Gallery-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 1600, 1200));
        Path zip = ClaudeTestFixtures.zip(tmp, "ok.zip", entries);
        ClaudeValidationReport r = validator.validate(ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(a)),
                "t.xlsx", zip, "ok.zip").report;
        assertThat(r.getZip().getIssues()).isEmpty();
        assertThat(row(r, 2).getImages()).hasSize(5);
        assertThat(row(r, 2).getErrors()).isEmpty();
        assertThat(row(r, 2).getStatus()).isEqualTo("VALID");

        entries.put("Victory-Ace/Victory-Ace_ProjectLogo_My-Property-Fact.png", ClaudeTestFixtures.image("png", 792, 203));
        entries.put("Other-Project_Gallery-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 1600, 1200));
        entries.put("Wrong-Folder/Victory-Ace_Gallery-2_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 1600, 1200));
        Path bad = ClaudeTestFixtures.zip(tmp, "bad.zip", entries);
        ClaudeBatchValidator.Result res = validator.validate(ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(a)),
                "t.xlsx", bad, "bad.zip");
        assertThat(codes(row(res.report, 2).getErrors())).contains("IMAGE_UNKNOWN_TYPE", "IMAGE_FOLDER_MISMATCH");
        assertThat(res.report.getZip().getIssues()).anyMatch(i -> "IMAGE_PROJECT_NOT_IN_WORKBOOK".equals(i.getCode()));
        assertThat(res.batchBlocked).isTrue();
    }

    @Test
    void secondLogoAndMissingCoreImagesAreReported() throws Exception {
        Map<String, Object> a = ClaudeTestFixtures.validRow("Victory Ace", "Raheja Developers", "Gurugram", "Haryana", "R-1");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Victory-Ace_Logo_My-Property-Fact.png", ClaudeTestFixtures.image("png", 792, 203));
        entries.put("Victory-Ace_Logo-2_My-Property-Fact.png", ClaudeTestFixtures.image("png", 792, 203));
        Path zip = ClaudeTestFixtures.zip(tmp, "logo.zip", entries);
        ClaudeValidationReport r = validator.validate(ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(a)),
                "t.xlsx", zip, "logo.zip").report;
        assertThat(codes(row(r, 2).getErrors())).contains("IMAGE_MULTIPLE_LOGO");
        assertThat(codes(row(r, 2).getWarnings())).contains("IMAGE_CORE_MISSING");
    }
}
