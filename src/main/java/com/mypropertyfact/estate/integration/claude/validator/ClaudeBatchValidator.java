package com.mypropertyfact.estate.integration.claude.validator;

import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationReport;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeProjectClaim;
import com.mypropertyfact.estate.integration.claude.model.ClaudeParsedWorkbook;
import com.mypropertyfact.estate.integration.claude.model.ClaudeRowPlan;
import com.mypropertyfact.estate.integration.claude.model.ClaudeZipImage;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeWorkbookParser;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeZipInspector;
import com.mypropertyfact.estate.integration.claude.repository.ClaudeProjectClaimRepository;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService.*;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeColumn;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dry-run validation of one workbook + image ZIP. Reads the database, never writes it. Produces
 * both the report returned to Claude and the fully resolved row plans the draft importer executes.
 */
@Component
@RequiredArgsConstructor
public class ClaudeBatchValidator {

    private final ClaudeWorkbookParser parser;
    private final ClaudeZipInspector zipInspector;
    private final ClaudeLookupService lookup;
    private final ClaudeProjectClaimRepository claimRepository;
    private final ClaudeIntegrationProperties props;
    private final FileUtils fileUtils;

    public static final class Result {
        public final ClaudeValidationReport report = new ClaudeValidationReport();
        public final List<ClaudeRowPlan> plans = new ArrayList<>();
        /** Errors that block the whole batch (workbook or ZIP level). */
        public boolean batchBlocked;

        public List<ClaudeRowPlan> importable() {
            return plans.stream().filter(p -> p.state() == ClaudeRowPlan.State.VALID).collect(Collectors.toList());
        }
    }

    private static final List<ClaudeColumn> LONG_TEXT = List.of(
            ClaudeColumn.AMENITY_DESCRIPTION, ClaudeColumn.LOCATION_DESCRIPTION, ClaudeColumn.FLOOR_PLAN_DESCRIPTION,
            ClaudeColumn.LOCATION_ADVANTAGE, ClaudeColumn.PROJECT_WALKTHROUGH_DESCRIPTION, ClaudeColumn.META_DESCRIPTION,
            ClaudeColumn.BUILDER_DESCRIPTION);

    public Result validate(byte[] excelBytes, String excelFilename, Path zipPath, String zipFilename) {
        Result result = new Result();
        ClaudeValidationReport report = result.report;
        ClaudeValidationReport.WorkbookInfo info = report.getWorkbook();
        info.setFilename(excelFilename);

        ClaudeParsedWorkbook wb = parser.parse(excelBytes, props.getMaxRows());
        info.setFormat(wb.format);
        info.setSheetName(wb.sheetName);
        info.getSheetNames().addAll(wb.sheetNames);
        info.setDataRows(wb.rows.size());
        if (!wb.rows.isEmpty()) {
            info.setFirstDataRow(wb.rows.get(0).excelRow());
            info.setLastDataRow(wb.rows.get(wb.rows.size() - 1).excelRow());
        }
        report.getWorkbookIssues().addAll(wb.issues);

        boolean headersOk = !wb.unreadable && checkHeaders(wb, info, report);
        if (headersOk && !wb.rows.isEmpty()) {
            MasterData md = lookup.loadMasterData();
            for (ClaudeParsedWorkbook.Row row : wb.rows) {
                result.plans.add(buildPlan(row, md));
            }
            checkWorkbookDuplicates(result.plans);
            checkExistingDuplicates(result.plans);
            checkSharedBuilders(result.plans);
            checkDataQuality(result.plans);
            if (zipPath != null) {
                checkImages(zipPath, zipFilename, result);
            } else {
                for (ClaudeRowPlan p : result.plans) {
                    p.warning("NO_IMAGES", null, "No image ZIP was uploaded; the draft will have no images.", null);
                }
            }
        }

        fillReport(result);
        return result;
    }

    // ------------------------------------------------------------------ headers

    private boolean checkHeaders(ClaudeParsedWorkbook wb, ClaudeValidationReport.WorkbookInfo info, ClaudeValidationReport report) {
        Set<String> populated = new HashSet<>();
        wb.rows.forEach(r -> populated.addAll(r.values().keySet()));
        Set<String> seen = new HashSet<>();
        for (ClaudeParsedWorkbook.Header h : wb.headers) {
            ClaudeValidationReport.HeaderInfo hi = new ClaudeValidationReport.HeaderInfo();
            hi.setColumn(h.letter());
            hi.setRawHeader(h.raw());
            hi.setNormalizedHeader(h.normalized());
            if (!seen.add(h.normalized())) {
                hi.setStatus("DUPLICATE");
                info.getDetectedHeaders().add(hi);
                continue;
            }
            Optional<ClaudeColumn> col = ClaudeImporterSpec.column(h.normalized());
            Object[] faq = ClaudeImporterSpec.faqHeader(h.normalized());
            if (col.isPresent()) {
                hi.setMappedTo(col.get().target());
                hi.setStatus(switch (col.get().kind()) {
                    case REFERENCE_ONLY -> "REFERENCE_ONLY";
                    case NEW_BUILDER_ONLY -> "NEW_BUILDER_ONLY";
                    default -> "MAPPED";
                });
            } else if (faq != null) {
                hi.setMappedTo("project_faqs." + ("QUESTION".equals(faq[0]) ? "faq_question" : "faq_answer") + " (pair " + faq[1] + ")");
                hi.setStatus("FAQ");
                String partner = "QUESTION".equals(faq[0]) ? ClaudeImporterSpec.faqAnswerHeader((int) faq[1])
                        : ClaudeImporterSpec.faqQuestionHeader((int) faq[1]);
                if (!wb.headerIndex.containsKey(partner)) {
                    report.getWorkbookIssues().add(ClaudeValidationIssue.error("EXCEL_FAQ_HEADER_UNPAIRED",
                            "Header \"" + h.raw().trim() + "\" has no matching \"" + partner + "\" column.").column(h.letter()));
                }
            } else {
                hi.setStatus("UNKNOWN");
                info.getUnknownHeaders().add(h.raw());
                if (populated.contains(h.normalized())) {
                    report.getWorkbookIssues().add(ClaudeValidationIssue.error("EXCEL_UNKNOWN_HEADER",
                            "Column " + h.letter() + " \"" + h.raw().trim() + "\" is not supported by the importer and has values. "
                                    + "Remove it or rename it to a supported header; values are never silently dropped.").column(h.letter()));
                } else {
                    report.getWorkbookIssues().add(ClaudeValidationIssue.warning("EXCEL_UNKNOWN_EMPTY_HEADER",
                            "Column " + h.letter() + " \"" + h.raw().trim() + "\" is not supported (it is empty, so nothing is lost).").column(h.letter()));
                }
            }
            info.getDetectedHeaders().add(hi);
        }
        for (ClaudeColumn c : ClaudeImporterSpec.requiredColumns()) {
            if (!wb.headerIndex.containsKey(ClaudeText.normalizeHeader(c.header()))) {
                info.getMissingRequiredHeaders().add(c.header());
                report.getWorkbookIssues().add(ClaudeValidationIssue.error("EXCEL_MISSING_REQUIRED_HEADER",
                        "Required column \"" + c.header() + "\" is missing."));
            }
        }
        if (populated.contains(ClaudeText.normalizeHeader(ClaudeColumn.ID.header()))) {
            info.getUnsupportedFields().put(ClaudeColumn.ID.header(), ClaudeColumn.ID.target());
        }
        if (populated.contains(ClaudeText.normalizeHeader(ClaudeColumn.BUILDER_DESCRIPTION.header()))) {
            info.getUnsupportedFields().put(ClaudeColumn.BUILDER_DESCRIPTION.header(), ClaudeColumn.BUILDER_DESCRIPTION.target());
        }
        return info.getMissingRequiredHeaders().isEmpty()
                && report.getWorkbookIssues().stream().noneMatch(ClaudeValidationIssue::isError);
    }

    // ------------------------------------------------------------------ one row

    ClaudeRowPlan buildPlan(ClaudeParsedWorkbook.Row row, MasterData md) {
        ClaudeRowPlan p = new ClaudeRowPlan(row);
        p.sourceReference = val(row, ClaudeColumn.ID);
        p.projectName = val(row, ClaudeColumn.PROJECT_NAME);
        p.builderName = val(row, ClaudeColumn.BUILDER_NAME);
        p.cityName = val(row, ClaudeColumn.CITY_NAME);
        p.reraNo = val(row, ClaudeColumn.RERA_NO);
        p.reraKey = ClaudeText.isBlank(p.reraNo) ? null : ClaudeText.reraKey(p.reraNo);

        for (ClaudeColumn c : ClaudeColumn.values()) {
            String v = val(row, c);
            if (c.required() && ClaudeText.isBlank(v)) {
                p.error("REQUIRED_VALUE_MISSING", c.header(), c.header() + " is required.", null);
            }
            if (v != null && c.maxLength() > 0 && v.length() > c.maxLength()) {
                p.error("VALUE_TOO_LONG", c.header(), c.header() + " is " + v.length() + " characters; the database column holds "
                        + c.maxLength() + ".", v);
            }
        }
        if (p.projectName != null) {
            p.slug = fileUtils.generateSlug(p.projectName);
            if (p.slug.isEmpty()) {
                p.error("INVALID_PROJECT_NAME", ClaudeColumn.PROJECT_NAME.header(),
                        "PROJECT NAME produces an empty URL slug.", p.projectName);
            }
        }
        if (p.reraKey == null) {
            p.warning("RERA_MISSING", ClaudeColumn.RERA_NO.header(),
                    "RERA NO is blank; duplicate detection relies on slug and name+builder+city only.", null);
        }

        resolveGeography(p, row, md);
        p.projectStatusId = resolveSimple(p, md.statuses, val(row, ClaudeColumn.PROJECT_STATUS_NAME),
                ClaudeColumn.PROJECT_STATUS_NAME, "UNKNOWN_PROJECT_STATUS");
        p.projectTypeId = resolveSimple(p, md.types, val(row, ClaudeColumn.PROPERTY_TYPE_NAME),
                ClaudeColumn.PROPERTY_TYPE_NAME, "UNKNOWN_PROPERTY_TYPE");
        resolveBuilder(p, row, md);
        resolveAmenities(p, row, md);
        resolveConfiguration(p, row);
        resolveLocationAdvantage(p, row);
        resolveFaqs(p, row);
        resolveScalars(p, row);
        return p;
    }

    private void resolveGeography(ClaudeRowPlan p, ClaudeParsedWorkbook.Row row, MasterData md) {
        String countryName = val(row, ClaudeColumn.COUNTRY_NAME);
        String stateName = val(row, ClaudeColumn.STATE_NAME);
        String cityName = val(row, ClaudeColumn.CITY_NAME);

        if (countryName != null) {
            List<Ref> c = md.countries.getOrDefault(ClaudeText.lookupKey(countryName), List.of());
            if (c.isEmpty()) {
                p.error("UNKNOWN_COUNTRY", ClaudeColumn.COUNTRY_NAME.header(),
                        "Country \"" + countryName + "\" does not exist. New countries are never created by this import.", countryName);
            } else if (c.size() > 1) {
                p.error("AMBIGUOUS_COUNTRY", ClaudeColumn.COUNTRY_NAME.header(),
                        "Country \"" + countryName + "\" matches " + c.size() + " records.", countryName);
            } else {
                p.countryId = c.get(0).id();
            }
        }
        if (stateName != null) {
            List<StateRef> all = md.states.getOrDefault(ClaudeText.lookupKey(stateName), List.of());
            List<StateRef> s = p.countryId == null ? all
                    : all.stream().filter(x -> Objects.equals(x.countryId(), p.countryId)).toList();
            if (all.isEmpty()) {
                p.error("UNKNOWN_STATE", ClaudeColumn.STATE_NAME.header(),
                        "State \"" + stateName + "\" does not exist. New states are never created by this import.", stateName);
            } else if (s.isEmpty()) {
                p.error("STATE_COUNTRY_MISMATCH", ClaudeColumn.STATE_NAME.header(),
                        "State \"" + stateName + "\" exists but not under country \"" + countryName + "\".", stateName);
            } else if (s.size() > 1) {
                p.error("AMBIGUOUS_STATE", ClaudeColumn.STATE_NAME.header(),
                        "State \"" + stateName + "\" matches " + s.size() + " records.", stateName);
            } else {
                p.stateId = s.get(0).id();
            }
        }
        if (cityName != null) {
            List<CityRef> all = md.cities.getOrDefault(ClaudeText.lookupKey(cityName), List.of());
            if (all.isEmpty()) {
                p.error("UNKNOWN_CITY", ClaudeColumn.CITY_NAME.header(),
                        "City \"" + cityName + "\" does not exist. New cities are never created by this import.", cityName);
                return;
            }
            List<CityRef> inState = p.stateId == null ? all
                    : all.stream().filter(x -> Objects.equals(x.stateId(), p.stateId)).toList();
            if (inState.isEmpty()) {
                List<CityRef> stateless = all.stream().filter(x -> x.stateId() == null).toList();
                if (stateless.size() == 1) {
                    p.cityId = stateless.get(0).id();
                    p.warning("CITY_STATE_UNVERIFIED", ClaudeColumn.CITY_NAME.header(),
                            "City \"" + cityName + "\" has no state in master data, so STATE NAME could not be cross-checked.", cityName);
                } else {
                    String actual = all.stream().map(x -> x.stateName() == null ? "(none)" : x.stateName()).distinct()
                            .collect(Collectors.joining(", "));
                    p.error("CITY_STATE_MISMATCH", ClaudeColumn.CITY_NAME.header(),
                            "City \"" + cityName + "\" exists but under state " + actual + ", not \"" + val(row, ClaudeColumn.STATE_NAME) + "\".", cityName);
                }
            } else if (inState.size() > 1) {
                p.error("AMBIGUOUS_CITY", ClaudeColumn.CITY_NAME.header(),
                        "City \"" + cityName + "\" matches " + inState.size() + " records in that state.", cityName);
            } else {
                p.cityId = inState.get(0).id();
            }
        }
    }

    private Integer resolveSimple(ClaudeRowPlan p, Map<String, List<Ref>> map, String value, ClaudeColumn col, String unknownCode) {
        if (value == null) {
            return null;
        }
        List<Ref> found = map.getOrDefault(ClaudeText.lookupKey(value), List.of());
        if (found.isEmpty()) {
            String options = map.values().stream().flatMap(List::stream).map(Ref::name).sorted().collect(Collectors.joining(", "));
            p.error(unknownCode, col.header(), col.header() + " \"" + value + "\" does not exist. Existing values: " + options
                    + ". There is no fallback to a default value.", value);
            return null;
        }
        if (found.size() > 1) {
            p.error("AMBIGUOUS_" + unknownCode.replace("UNKNOWN_", ""), col.header(),
                    col.header() + " \"" + value + "\" matches " + found.size() + " records.", value);
            return null;
        }
        return found.get(0).id();
    }

    private void resolveBuilder(ClaudeRowPlan p, ClaudeParsedWorkbook.Row row, MasterData md) {
        String name = p.builderName;
        String desc = val(row, ClaudeColumn.BUILDER_DESCRIPTION);
        if (name == null) {
            if (desc != null) {
                p.unappliedFields.put(ClaudeColumn.BUILDER_DESCRIPTION.header(), desc);
            }
            return;
        }
        p.builderSlug = fileUtils.generateSlug(name);
        List<BuilderRef> byName = md.buildersByName.getOrDefault(ClaudeText.lookupKey(name), List.of());
        if (byName.size() > 1) {
            p.error("AMBIGUOUS_BUILDER", ClaudeColumn.BUILDER_NAME.header(),
                    "Builder \"" + name + "\" matches " + byName.size() + " existing builders: "
                            + byName.stream().map(b -> "#" + b.id()).collect(Collectors.joining(", ")), name);
            return;
        }
        BuilderRef existing = byName.isEmpty() ? null : byName.get(0);
        if (existing == null && !p.builderSlug.isEmpty()) {
            List<BuilderRef> bySlug = md.buildersBySlug.getOrDefault(p.builderSlug, List.of());
            if (bySlug.size() > 1) {
                p.error("AMBIGUOUS_BUILDER", ClaudeColumn.BUILDER_NAME.header(),
                        "Builder slug \"" + p.builderSlug + "\" matches " + bySlug.size() + " existing builders.", name);
                return;
            }
            if (bySlug.size() == 1) {
                existing = bySlug.get(0);
                p.warning("BUILDER_MATCHED_BY_SLUG", ClaudeColumn.BUILDER_NAME.header(),
                        "Builder \"" + name + "\" matched existing builder \"" + existing.name() + "\" (#" + existing.id() + ") by URL slug.", name);
            }
        }
        if (existing != null) {
            p.builderId = existing.id();
            p.newBuilder = false;
            if (desc != null) {
                p.unappliedFields.put(ClaudeColumn.BUILDER_DESCRIPTION.header(), desc);
                p.warning("BUILDER_DESCRIPTION_NOT_APPLIED", ClaudeColumn.BUILDER_DESCRIPTION.header(),
                        "Builder \"" + existing.name() + "\" (#" + existing.id() + ") already exists; its profile is never changed by this "
                                + "import, so BUILDER DESCRIPTION is not written. The value is kept in the job result.", null);
            }
        } else {
            if (p.builderSlug.isEmpty()) {
                p.error("INVALID_BUILDER_NAME", ClaudeColumn.BUILDER_NAME.header(), "BUILDER NAME produces an empty slug.", name);
                return;
            }
            p.newBuilder = true;
            p.builderDescription = desc;
            p.warning("NEW_BUILDER", ClaudeColumn.BUILDER_NAME.header(),
                    "Builder \"" + name + "\" does not exist and will be created (name, slug"
                            + (desc != null ? ", description from BUILDER DESCRIPTION" : "") + " only).", name);
        }
    }

    private void resolveAmenities(ClaudeRowPlan p, ClaudeParsedWorkbook.Row row, MasterData md) {
        String value = val(row, ClaudeColumn.PROJECT_AMENITIES);
        if (value == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String token : ClaudeImporterSpec.splitListColumn(value)) {
            String key = ClaudeText.amenityKey(token);
            if (key.isEmpty()) {
                continue;
            }
            if (!seen.add(key)) {
                p.warning("DUPLICATE_AMENITY", ClaudeColumn.PROJECT_AMENITIES.header(), "Amenity \"" + token + "\" is listed twice.", token);
                continue;
            }
            List<Ref> found = md.amenities.getOrDefault(key, List.of());
            if (found.isEmpty()) {
                String hint = suggestAmenity(key, md);
                p.error("UNKNOWN_AMENITY", ClaudeColumn.PROJECT_AMENITIES.header(),
                        "Amenity \"" + token + "\" does not match any existing amenity"
                                + (hint != null ? " (did you mean \"" + hint + "\"?)" : "") + ". Amenities are never created or skipped silently.", token);
            } else if (found.size() > 1) {
                p.error("AMBIGUOUS_AMENITY", ClaudeColumn.PROJECT_AMENITIES.header(),
                        "Amenity \"" + token + "\" matches " + found.size() + " amenities.", token);
            } else {
                p.amenityIds.add(found.get(0).id());
                p.amenityTitles.add(found.get(0).name());
            }
        }
    }

    /** Suggestion only, never applied: same letters ignoring spaces. */
    private static String suggestAmenity(String key, MasterData md) {
        String squashed = key.replace(" ", "");
        for (Map.Entry<String, List<Ref>> e : md.amenities.entrySet()) {
            if (e.getKey().replace(" ", "").equals(squashed)) {
                return e.getValue().get(0).name();
            }
        }
        return null;
    }

    private void resolveConfiguration(ClaudeRowPlan p, ClaudeParsedWorkbook.Row row) {
        String value = val(row, ClaudeColumn.PROJECT_CONFIGURATION);
        if (value == null) {
            return;
        }
        ClaudeImporterSpec.ConfigurationParse parse = ClaudeImporterSpec.parseConfiguration(value);
        p.floorPlans.addAll(parse.plans());
        for (String seg : parse.unparsedSegments()) {
            p.warning("CONFIGURATION_SEGMENT_UNPARSED", ClaudeColumn.PROJECT_CONFIGURATION.header(),
                    "\"" + seg + "\" is not in the \"<type>-<area> sq.ft\" format, so no complete floor plan row is created for it "
                            + "(the full text is still saved in project_configuration).", seg);
        }
    }

    private void resolveLocationAdvantage(ClaudeRowPlan p, ClaudeParsedWorkbook.Row row) {
        String value = val(row, ClaudeColumn.LOCATION_ADVANTAGE);
        if (value == null) {
            return;
        }
        ClaudeImporterSpec.LocationAdvantageParse parse = ClaudeImporterSpec.parseLocationAdvantage(value);
        p.locationBenefits.addAll(parse.benefits());
        if (parse.unparsedSegments().isEmpty()) {
            return;
        }
        String col = ClaudeColumn.LOCATION_ADVANTAGE.header();
        p.unappliedFields.put(col, parse.benefits().isEmpty() ? value : String.join(" | ", parse.unparsedSegments()));
        String msg = parse.benefits().isEmpty()
                ? "LOCATION ADVANTAGE is free text; the importer only stores \"Place-Name-4.2-Km\" or \"Place Name (4.2 Km)\" segments "
                + "as location benefits, so none can be created. The text is kept in the job result, not on the project."
                : parse.unparsedSegments().size() + " LOCATION ADVANTAGE segment(s) are not in a supported format and are not stored "
                + "(kept in the job result).";
        if (props.isStrictLocationAdvantage()) {
            p.error("LOCATION_ADVANTAGE_UNPARSED", col, msg, value);
        } else {
            p.warning("LOCATION_ADVANTAGE_UNPARSED", col, msg, value);
        }
    }

    private void resolveFaqs(ClaudeRowPlan p, ClaudeParsedWorkbook.Row row) {
        for (int n = 1; n <= ClaudeImporterSpec.MAX_FAQ_PAIRS; n++) {
            String q = row.get(ClaudeImporterSpec.faqQuestionHeader(n));
            String a = row.get(ClaudeImporterSpec.faqAnswerHeader(n));
            if (q == null && a == null) {
                continue;
            }
            if (q == null || a == null) {
                p.error("FAQ_INCOMPLETE", q == null ? "Question " + n : "Answer " + n,
                        "FAQ pair " + n + " has " + (q == null ? "an answer but no question" : "a question but no answer") + ".", q == null ? a : q);
                continue;
            }
            p.faqs.add(new ClaudeRowPlan.Faq(q, a));
        }
    }

    private void resolveScalars(ClaudeRowPlan p, ClaudeParsedWorkbook.Row row) {
        put(p, "projectLocality", val(row, ClaudeColumn.PROJECT_LOCALITY));
        put(p, "projectConfiguration", val(row, ClaudeColumn.PROJECT_CONFIGURATION));
        put(p, "projectPrice", val(row, ClaudeColumn.PROJECT_PRICE));
        put(p, "reraNo", p.reraNo);
        put(p, "reraWebsite", val(row, ClaudeColumn.RERA_WEBSITE));
        put(p, "ivrNo", val(row, ClaudeColumn.IVR_NO));
        put(p, "metaTitle", val(row, ClaudeColumn.META_TITLE));
        put(p, "metaDescription", val(row, ClaudeColumn.META_DESCRIPTION));
        put(p, "metaKeyword", val(row, ClaudeColumn.META_KEYWORD));
        put(p, "amenityDesc", val(row, ClaudeColumn.AMENITY_DESCRIPTION));
        put(p, "floorPlanDesc", val(row, ClaudeColumn.FLOOR_PLAN_DESCRIPTION));
        put(p, "locationDesc", val(row, ClaudeColumn.LOCATION_DESCRIPTION));
        p.walkthroughDescription = val(row, ClaudeColumn.PROJECT_WALKTHROUGH_DESCRIPTION);
        p.aboutDescription = val(row, ClaudeColumn.PROJECT_ABOUT_SHORT_DESCRIPTION);

        String site = val(row, ClaudeColumn.RERA_WEBSITE);
        if (site != null && !site.matches("(?i)^https?://\\S+$")) {
            p.warning("RERA_WEBSITE_NOT_URL", ClaudeColumn.RERA_WEBSITE.header(), "RERA WEBSITE is not an http(s) URL.", site);
        }
        String ivr = val(row, ClaudeColumn.IVR_NO);
        if (ivr != null && !ivr.matches("^\\+?[0-9 -]{6,20}$")) {
            p.warning("IVR_NOT_PHONE", ClaudeColumn.IVR_NO.header(), "IVR NO does not look like a phone number.", ivr);
        }
        String price = val(row, ClaudeColumn.PROJECT_PRICE);
        if (price != null && price.matches("^[0-9]+(\\.[0-9]+)?$")) {
            p.issues.add(ClaudeValidationIssue.info("PRICE_BARE_NUMBER",
                            "PROJECT PRICE \"" + price + "\" has no unit; it is stored as this exact text, as the existing importer does.")
                    .row(p.excelRow).column(ClaudeColumn.PROJECT_PRICE.header()).value(price));
        }
    }

    private static void put(ClaudeRowPlan p, String field, String value) {
        if (!ClaudeText.isBlank(value)) {
            p.projectFields.put(field, value.trim());
        }
    }

    // ------------------------------------------------------------------ batch-level checks

    private void checkWorkbookDuplicates(List<ClaudeRowPlan> plans) {
        Map<String, Integer> slugs = new HashMap<>();
        Map<String, Integer> reras = new HashMap<>();
        Map<String, Integer> nbc = new HashMap<>();
        for (ClaudeRowPlan p : plans) {
            if (p.slug != null && !p.slug.isEmpty()) {
                Integer first = slugs.putIfAbsent(p.slug, p.excelRow);
                if (first != null) {
                    p.error("DUPLICATE_IN_WORKBOOK", ClaudeColumn.PROJECT_NAME.header(),
                            "Same project URL slug \"" + p.slug + "\" as row " + first + ".", p.projectName);
                }
            }
            if (p.reraKey != null) {
                Integer first = reras.putIfAbsent(p.reraKey, p.excelRow);
                if (first != null) {
                    p.error("DUPLICATE_IN_WORKBOOK", ClaudeColumn.RERA_NO.header(), "Same RERA NO as row " + first + ".", p.reraNo);
                }
            }
            if (p.projectName != null && p.builderName != null && p.cityName != null) {
                Integer first = nbc.putIfAbsent(p.nameBuilderCityKey(), p.excelRow);
                if (first != null && !Objects.equals(slugs.get(p.slug), first)) {
                    p.error("DUPLICATE_IN_WORKBOOK", ClaudeColumn.PROJECT_NAME.header(),
                            "Same project name + builder + city as row " + first + ".", p.projectName);
                }
            }
        }
    }

    /** Existing projects and earlier Claude claims. Rows found here become DUPLICATE_REVIEW_REQUIRED. */
    private void checkExistingDuplicates(List<ClaudeRowPlan> plans) {
        List<String> allKeys = new ArrayList<>();
        for (ClaudeRowPlan p : plans) {
            if (p.slug == null || p.slug.isEmpty()) {
                continue;
            }
            for (ProjectRef ref : lookup.projectsBySlug(p.slug)) {
                duplicate(p, ref, "slug \"" + p.slug + "\"");
            }
            for (ProjectRef ref : lookup.projectsByReraKey(p.reraKey)) {
                duplicate(p, ref, "RERA NO \"" + p.reraNo + "\"");
            }
            for (ProjectRef ref : lookup.projectsByNameBuilderCity(p.projectName, p.builderId, p.cityId)) {
                duplicate(p, ref, "project name + builder + city");
            }
            allKeys.addAll(p.claimKeys());
        }
        if (allKeys.isEmpty()) {
            return;
        }
        Map<String, ClaudeProjectClaim> claims = new HashMap<>();
        claimRepository.findByClaimKeyIn(allKeys).forEach(c -> claims.put(c.getClaimKey(), c));
        for (ClaudeRowPlan p : plans) {
            if (p.slug == null || p.slug.isEmpty()) {
                continue;
            }
            for (String key : p.claimKeys()) {
                ClaudeProjectClaim c = claims.get(key);
                if (c == null) {
                    continue;
                }
                if (c.getProjectId() != null && lookup.projectExists(c.getProjectId())) {
                    if (!p.duplicateReasons.isEmpty() && Objects.equals(p.existingProjectId, c.getProjectId())) {
                        continue;
                    }
                    p.duplicateReasons.add("Already imported by Claude job " + c.getJobId() + " as project #" + c.getProjectId()
                            + " (" + key.substring(0, key.indexOf(':')) + ")");
                    if (p.existingProjectId == null) {
                        p.existingProjectId = c.getProjectId();
                    }
                } else {
                    p.staleClaimIds.add(c.getId());
                    p.warning("STALE_CLAIM", null, "An earlier Claude import (job " + c.getJobId() + ") claimed this identity but its "
                            + "project no longer exists; the claim will be released on import.", key);
                }
            }
        }
    }

    private static void duplicate(ClaudeRowPlan p, ProjectRef ref, String by) {
        String reason = "Matches existing project #" + ref.id() + " \"" + ref.name() + "\" ("
                + (ref.published() ? "published" : "draft") + ") by " + by;
        if (!p.duplicateReasons.contains(reason)) {
            p.duplicateReasons.add(reason);
        }
        if (p.existingProjectId == null) {
            p.existingProjectId = ref.id();
        }
    }

    /** A new builder used by several rows is created once; only the first row's description is used. */
    private void checkSharedBuilders(List<ClaudeRowPlan> plans) {
        Map<String, ClaudeRowPlan> firstNew = new HashMap<>();
        for (ClaudeRowPlan p : plans) {
            if (!p.newBuilder || p.builderSlug == null) {
                continue;
            }
            ClaudeRowPlan first = firstNew.putIfAbsent(p.builderSlug, p);
            if (first != null && p.builderDescription != null) {
                if (!p.builderDescription.equals(first.builderDescription)) {
                    p.unappliedFields.put(ClaudeColumn.BUILDER_DESCRIPTION.header(), p.builderDescription);
                    p.warning("BUILDER_DESCRIPTION_NOT_APPLIED", ClaudeColumn.BUILDER_DESCRIPTION.header(),
                            "New builder \"" + p.builderName + "\" is first created by row " + first.excelRow
                                    + "; that row's description is used and this one is kept in the job result.", null);
                }
                p.builderDescription = null;
            }
        }
    }

    private void checkDataQuality(List<ClaudeRowPlan> plans) {
        for (ClaudeColumn col : LONG_TEXT) {
            Map<String, ClaudeRowPlan> seen = new HashMap<>();
            for (ClaudeRowPlan p : plans) {
                String v = val(p.row, col);
                if (v == null || v.length() < 40) {
                    continue;
                }
                ClaudeRowPlan first = seen.putIfAbsent(ClaudeText.lookupKey(v), p);
                if (first != null && !(col == ClaudeColumn.BUILDER_DESCRIPTION
                        && ClaudeText.lookupKey(first.builderName).equals(ClaudeText.lookupKey(p.builderName)))) {
                    p.warning("DUPLICATE_TEXT_ACROSS_ROWS", col.header(),
                            col.header() + " is identical to row " + first.excelRow + " (\"" + first.projectName + "\"). Check it was not copied by mistake.", null);
                }
            }
        }
        for (ClaudeRowPlan p : plans) {
            if (p.projectName == null) {
                continue;
            }
            String own = p.projectName.toLowerCase(Locale.ROOT);
            for (ClaudeColumn col : LONG_TEXT) {
                String v = val(p.row, col);
                if (v == null) {
                    continue;
                }
                String text = v.toLowerCase(Locale.ROOT);
                for (ClaudeRowPlan other : plans) {
                    if (other == p || other.projectName == null || other.projectName.length() < 6) {
                        continue;
                    }
                    String otherName = other.projectName.toLowerCase(Locale.ROOT);
                    if (!own.contains(otherName) && text.contains(otherName) && !text.contains(own)) {
                        p.warning("TEXT_MENTIONS_OTHER_PROJECT", col.header(),
                                col.header() + " mentions \"" + other.projectName + "\" (row " + other.excelRow + ") but not this project's name.", null);
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ images

    private void checkImages(Path zipPath, String zipFilename, Result result) {
        ClaudeZipInspector.Result zr = zipInspector.inspect(zipPath);
        ClaudeValidationReport.ZipInfo zi = new ClaudeValidationReport.ZipInfo();
        zi.setFilename(zipFilename);
        zi.setEntries(zr.entries);
        zi.setImages(zr.images.size());
        zi.setExtractedBytes(zr.extractedBytes);
        result.report.setZip(zi);

        Map<String, ClaudeRowPlan> bySlug = new HashMap<>();
        for (ClaudeRowPlan p : result.plans) {
            if (p.slug != null && !p.slug.isEmpty()) {
                bySlug.putIfAbsent(p.slug, p);
            }
        }
        // Attach file-level problems to their row when the file name identifies one.
        for (ClaudeValidationIssue issue : zr.issues) {
            ClaudeRowPlan owner = issue.getValue() == null ? null : rowForFile(issue.getValue(), bySlug);
            if (owner != null) {
                issue.setExcelRow(owner.excelRow);
                owner.issues.add(issue);
            } else {
                zi.getIssues().add(issue);
            }
        }
        if (zr.unreadable) {
            return;
        }
        for (ClaudeZipImage img : zr.images) {
            if (img.projectKey() == null || img.projectKey().isEmpty() || img.typeToken() == null || img.typeToken().isEmpty()) {
                zi.getIssues().add(ClaudeValidationIssue.error("IMAGE_BAD_NAME",
                        "\"" + img.fileName() + "\" must be named <Project-Name-With-Hyphens>_<Type>_My-Property-Fact.<ext>.").value(img.entryName()));
                continue;
            }
            String slug = fileUtils.generateSlug(ClaudeImporterSpec.zipKeyToName(img.projectKey()));
            ClaudeRowPlan owner = bySlug.get(slug);
            if (owner == null) {
                zi.getIssues().add(ClaudeValidationIssue.error("IMAGE_PROJECT_NOT_IN_WORKBOOK",
                        "\"" + img.fileName() + "\" is for project key \"" + img.projectKey() + "\" (slug \"" + slug
                                + "\"), which is not a row in this workbook. Images are only attached to projects created by this batch.")
                        .value(img.entryName()));
                continue;
            }
            if (img.kind() == null) {
                owner.error("IMAGE_UNKNOWN_TYPE", null, "\"" + img.fileName() + "\": image type \"" + img.typeToken()
                        + "\" is not recognised (the existing importer would save it as a gallery image). Use \""
                        + ClaudeImporterSpec.imageTypeHint(img.typeToken()) + "\". Allowed: Logo, Thumbnail, Location, Desktop-N, Mobile-N, Gallery-N.", img.entryName());
                continue;
            }
            if (!img.folder().isEmpty()) {
                String lastFolder = img.folder().substring(img.folder().lastIndexOf('/') + 1);
                String folderSlug = fileUtils.generateSlug(ClaudeImporterSpec.zipKeyToName(lastFolder));
                if (!folderSlug.equals(slug)) {
                    owner.error("IMAGE_FOLDER_MISMATCH", null, "\"" + img.entryName() + "\" is in folder \"" + lastFolder
                            + "\" but its file name is for \"" + img.projectKey() + "\".", img.entryName());
                    continue;
                }
            }
            owner.images.add(img);
        }
        for (ClaudeRowPlan p : result.plans) {
            checkRowImages(p);
        }
    }

    private void checkRowImages(ClaudeRowPlan p) {
        Map<ImageKind, Integer> counts = new EnumMap<>(ImageKind.class);
        Set<String> altTypes = new HashSet<>();
        for (ClaudeZipImage img : p.images) {
            counts.merge(img.kind(), 1, Integer::sum);
            if (!altTypes.add(img.typeToken().toLowerCase(Locale.ROOT))) {
                p.error("IMAGE_DUPLICATE_TYPE", null, "Image type \"" + img.typeToken() + "\" is used twice for this project; "
                        + "number them (Gallery-1, Gallery-2…).", img.entryName());
            }
            ImageKind k = img.kind();
            if (img.width() < k.boxWidth && img.height() < k.boxHeight) {
                p.warning("IMAGE_WILL_BE_UPSCALED", null, "\"" + img.fileName() + "\" is " + img.width() + "x" + img.height()
                        + "; it will be upscaled to fit " + k.boxWidth + "x" + k.boxHeight + " like the existing importer does.", img.entryName());
            }
            double ratio = (double) img.width() / img.height();
            double boxRatio = (double) k.boxWidth / k.boxHeight;
            if (Math.abs(ratio - boxRatio) / boxRatio > 0.05) {
                p.warning("IMAGE_ASPECT_RATIO", null, "\"" + img.fileName() + "\" (" + img.width() + "x" + img.height() + ") does not have the "
                        + k.name().toLowerCase(Locale.ROOT) + " aspect ratio " + k.boxWidth + ":" + k.boxHeight + "; it is fitted, not cropped.", img.entryName());
            }
        }
        for (ImageKind k : ImageKind.values()) {
            if (k.singlePerProject() && counts.getOrDefault(k, 0) > 1) {
                p.error("IMAGE_MULTIPLE_" + k.name(), null, "Only one " + k.name().toLowerCase(Locale.ROOT) + " image is allowed per project.", null);
            }
        }
        List<String> missing = new ArrayList<>();
        for (ImageKind k : List.of(ImageKind.DESKTOP, ImageKind.MOBILE, ImageKind.THUMBNAIL)) {
            if (!counts.containsKey(k)) {
                missing.add(k.name().toLowerCase(Locale.ROOT));
            }
        }
        if (!missing.isEmpty() && !p.issues.stream().anyMatch(i -> "NO_IMAGES".equals(i.getCode()))) {
            String msg = "Missing core images: " + String.join(", ", missing) + ".";
            if (props.isRequireCoreImages()) {
                p.error("IMAGE_CORE_MISSING", null, msg, null);
            } else {
                p.warning("IMAGE_CORE_MISSING", null, msg, null);
            }
        }
    }

    private ClaudeRowPlan rowForFile(String entryName, Map<String, ClaudeRowPlan> bySlug) {
        String n = entryName.replace('\\', '/');
        String file = n.substring(n.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        String[] parts = base.split("_");
        if (parts.length < 2) {
            return null;
        }
        return bySlug.get(fileUtils.generateSlug(ClaudeImporterSpec.zipKeyToName(parts[0])));
    }

    // ------------------------------------------------------------------ report

    private void fillReport(Result result) {
        ClaudeValidationReport r = result.report;
        boolean batchErrors = r.getWorkbookIssues().stream().anyMatch(ClaudeValidationIssue::isError)
                || (r.getZip() != null && r.getZip().getIssues().stream().anyMatch(ClaudeValidationIssue::isError));
        result.batchBlocked = batchErrors;
        int errors = (int) r.getWorkbookIssues().stream().filter(ClaudeValidationIssue::isError).count();
        int warnings = (int) r.getWorkbookIssues().stream().filter(i -> !i.isError()).count();
        if (r.getZip() != null) {
            errors += (int) r.getZip().getIssues().stream().filter(ClaudeValidationIssue::isError).count();
            warnings += (int) r.getZip().getIssues().stream().filter(i -> !i.isError()).count();
        }
        int importable = 0, blocked = 0, dups = 0;
        for (ClaudeRowPlan p : result.plans) {
            ClaudeValidationReport.RowReport rr = toRowReport(p);
            r.getRows().add(rr);
            errors += rr.getErrors().size();
            warnings += rr.getWarnings().size();
            switch (p.state()) {
                case VALID -> importable++;
                case BLOCKED -> blocked++;
                case DUPLICATE -> dups++;
            }
            p.unappliedFields.forEach((k, v) -> {
                if (ClaudeColumn.LOCATION_ADVANTAGE.header().equals(k)) {
                    r.getWorkbook().getUnsupportedFields().putIfAbsent(k,
                            "Only \"Name-4.2-Km\" / \"Name (4.2 Km)\" segments become location benefits; other text is kept in the job result.");
                }
            });
        }
        r.setTotalRows(result.plans.size());
        r.setImportableRows(batchErrors ? 0 : importable);
        r.setBlockedRows(blocked);
        r.setDuplicateRows(dups);
        r.setErrorCount(errors);
        r.setWarningCount(warnings);
        r.setValid(errors == 0 && dups == 0 && !result.plans.isEmpty());
    }

    public static ClaudeValidationReport.RowReport toRowReport(ClaudeRowPlan p) {
        ClaudeValidationReport.RowReport rr = new ClaudeValidationReport.RowReport();
        rr.setExcelRow(p.excelRow);
        rr.setSourceReference(p.sourceReference);
        rr.setProjectName(p.projectName);
        rr.setBuilderName(p.builderName);
        rr.setCityName(p.cityName);
        rr.setReraNo(p.reraNo);
        rr.setPredictedSlug(p.slug);
        rr.setStatus(switch (p.state()) {
            case VALID -> "VALID";
            case BLOCKED -> "BLOCKED";
            case DUPLICATE -> "DUPLICATE_REVIEW_REQUIRED";
        });
        rr.setExistingProjectId(p.existingProjectId);
        rr.getDuplicateReasons().addAll(p.duplicateReasons);
        rr.setNewBuilder(p.newBuilder);
        rr.setBuilderId(p.builderId);
        rr.getAmenitiesMatched().addAll(p.amenityTitles);
        rr.setFloorPlans(p.floorPlans.size());
        rr.setLocationBenefits(p.locationBenefits.size());
        rr.setFaqs(p.faqs.size());
        for (ClaudeZipImage img : p.images) {
            ClaudeValidationReport.ImageInfo ii = new ClaudeValidationReport.ImageInfo();
            ii.setFile(img.entryName());
            ii.setType(img.typeToken());
            ii.setSlot(img.kind().name());
            ii.setWidth(img.width());
            ii.setHeight(img.height());
            ii.setFormat(img.format());
            rr.getImages().add(ii);
        }
        for (ClaudeValidationIssue i : p.issues) {
            (i.isError() ? rr.getErrors() : rr.getWarnings()).add(i);
        }
        rr.getUnappliedFields().putAll(p.unappliedFields);
        return rr;
    }

    private static String val(ClaudeParsedWorkbook.Row row, ClaudeColumn col) {
        return ClaudeText.trimToNull(row.get(ClaudeText.normalizeHeader(col.header())));
    }
}
