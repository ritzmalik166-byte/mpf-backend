package com.mypropertyfact.estate.integration.claude.spec;

import com.mypropertyfact.estate.integration.claude.util.ClaudeText;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parsing rules of the existing {@code ProjectExcelUploadService}, reproduced (not called) so the
 * Claude draft importer reads a workbook and an image ZIP exactly the way the existing importer
 * does, plus the stricter checks the connector adds. Keep in sync with that class if it changes.
 */
public final class ClaudeImporterSpec {

    private ClaudeImporterSpec() {
    }

    /** Headers of the reference workbook, in order, exactly as they appear (note the leading space in F). */
    public static final List<String> REFERENCE_WORKBOOK_HEADERS = List.of(
            "ID", "BUILDER NAME", "PROJECT NAME", "PROPERTY TYPE NAME", "PROJECT STATUS NAME",
            " PROJECT CONFIGURATION", "PROJECT LOCALITY", "CITY NAME", "STATE NAME", "COUNTRY NAME",
            "RERA NO", "RERA WEBSITE", "IVR NO", "PROJECT PRICE", "META TITLE", "META DESCRIPTION",
            "META KEYWORD", "AMENITY DESCRIPTION", "LOCATION DESCRIPTION", "FLOOR PLAN DESCRIPTION",
            "BUILDER DESCRIPTION", "PROJECT Amenities", "LOCATION ADVANTAGE", "PROJECT WALKTHROUGH DESCRIPTION",
            "Question 1", "Answer 1", "Question 2", "Answer 2", "Question 3", "Answer 3", "Question 4", "Answer 4");

    /** FAQ pairs read by the existing importer: Question 1..20 / Answer 1..20. */
    public static final int MAX_FAQ_PAIRS = 20;

    private static final Pattern FAQ_HEADER = Pattern.compile("^(QUESTION|ANSWER) ([0-9]{1,2})$");

    private static final Map<String, ClaudeColumn> BY_HEADER = new HashMap<>();

    static {
        for (ClaudeColumn c : ClaudeColumn.values()) {
            BY_HEADER.put(ClaudeText.normalizeHeader(c.header()), c);
        }
    }

    public static Optional<ClaudeColumn> column(String normalizedHeader) {
        return Optional.ofNullable(BY_HEADER.get(normalizedHeader));
    }

    /** @return {"QUESTION"|"ANSWER", n} for a valid FAQ header, else null. */
    public static Object[] faqHeader(String normalizedHeader) {
        Matcher m = FAQ_HEADER.matcher(normalizedHeader);
        if (!m.matches()) {
            return null;
        }
        int n = Integer.parseInt(m.group(2));
        if (n < 1 || n > MAX_FAQ_PAIRS) {
            return null;
        }
        return new Object[]{m.group(1), n};
    }

    public static boolean isKnownHeader(String normalizedHeader) {
        return BY_HEADER.containsKey(normalizedHeader) || faqHeader(normalizedHeader) != null;
    }

    public static List<ClaudeColumn> requiredColumns() {
        List<ClaudeColumn> list = new ArrayList<>();
        for (ClaudeColumn c : ClaudeColumn.values()) {
            if (c.required()) {
                list.add(c);
            }
        }
        return list;
    }

    public static String faqQuestionHeader(int n) {
        return "QUESTION " + n;
    }

    public static String faqAnswerHeader(int n) {
        return "ANSWER " + n;
    }

    // ------------------------------------------------------------------ list columns

    /** Same separators as the existing importer's {@code splitListColumn}: {@code |}, {@code _}, {@code ,}. */
    public static List<String> splitListColumn(String value) {
        if (value == null || value.trim().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split("\\s*\\|\\s*|_|,")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ PROJECT CONFIGURATION

    public record FloorPlanSpec(String planType, Double areaSqft, Double areaSqmt) {
    }

    public record ConfigurationParse(List<FloorPlanSpec> plans, List<String> unparsedSegments) {
    }

    private static final Pattern AREA = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:sq\\.?ft|sq\\.?\\s*ft|sqft)?", Pattern.CASE_INSENSITIVE);

    /**
     * Same rules as the existing {@code saveFloorPlansFromConfiguration}: comma separated, plan type
     * before the first {@code -}, area after it. Segments the existing importer would silently skip
     * are returned as {@code unparsedSegments} so they can be reported.
     */
    public static ConfigurationParse parseConfiguration(String configuration) {
        List<FloorPlanSpec> plans = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        if (configuration == null || configuration.trim().isEmpty()) {
            return new ConfigurationParse(plans, unparsed);
        }
        for (String part : configuration.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int dash = trimmed.indexOf('-');
            if (dash <= 0 || dash == trimmed.length() - 1) {
                unparsed.add(trimmed);
                continue;
            }
            String planType = trimmed.substring(0, dash).trim();
            String areaStr = trimmed.substring(dash + 1).trim();
            if (planType.isEmpty()) {
                unparsed.add(trimmed);
                continue;
            }
            Double sqft = null;
            Matcher m = AREA.matcher(areaStr);
            if (m.find()) {
                try {
                    sqft = Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {
                    // stays null, same as the existing importer
                }
            }
            if (sqft == null) {
                unparsed.add(trimmed);
            }
            Double sqmt = sqft == null ? null : Math.round(sqft * 0.092903 * 100.0) / 100.0;
            plans.add(new FloorPlanSpec(planType, sqft, sqmt));
        }
        return new ConfigurationParse(plans, unparsed);
    }

    // ------------------------------------------------------------------ LOCATION ADVANTAGE

    public record LocationBenefitSpec(String name, String distance) {
    }

    public record LocationAdvantageParse(List<LocationBenefitSpec> benefits, List<String> unparsedSegments) {
    }

    private static final Pattern LEGACY_BENEFIT = Pattern.compile("^(.+)-([0-9]+(?:\\.[0-9]+)?)-Km$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN_BENEFIT = Pattern.compile(
            "^(.+?)\\s*\\(([0-9]+(?:\\.[0-9]+)?)\\s*Km\\)\\s*$", Pattern.CASE_INSENSITIVE);

    /** Same two formats as the existing {@code saveLocationAdvantagesFromColumn}. */
    public static LocationAdvantageParse parseLocationAdvantage(String value) {
        List<LocationBenefitSpec> benefits = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        for (String segment : splitListColumn(value)) {
            Matcher legacy = LEGACY_BENEFIT.matcher(segment);
            if (legacy.matches()) {
                benefits.add(new LocationBenefitSpec(legacy.group(1).trim().replace("-", " "), legacy.group(2) + " Km"));
                continue;
            }
            Matcher paren = PAREN_BENEFIT.matcher(segment);
            if (paren.matches()) {
                benefits.add(new LocationBenefitSpec(paren.group(1).trim(), paren.group(2) + " Km"));
                continue;
            }
            unparsed.add(segment);
        }
        return new LocationAdvantageParse(benefits, unparsed);
    }

    // ------------------------------------------------------------------ images

    public enum ImageKind {
        DESKTOP(2508, 1200), MOBILE(800, 800), LOGO(792, 203), LOCATION(1200, 800), THUMBNAIL(800, 800), GALLERY(1600, 1200);

        /** Box the existing importer resizes into (fit-within, aspect kept). */
        public final int boxWidth;
        public final int boxHeight;

        ImageKind(int w, int h) {
            this.boxWidth = w;
            this.boxHeight = h;
        }

        public boolean singlePerProject() {
            return this == LOGO || this == LOCATION || this == THUMBNAIL;
        }
    }

    /**
     * Strict image-type token. The existing importer uses a prefix match and saves anything unknown
     * as gallery; the connector instead rejects unknown tokens (e.g. {@code ProjectLogo}) so nothing
     * lands in the wrong slot silently. Accepted tokens all map to the same slot the existing
     * importer would choose.
     */
    private static final Pattern IMAGE_TYPE = Pattern.compile(
            "^(logo|thumbnail|location(?:map)?|desktop(?:banner)?|mobile(?:banner)?|gallery)(?:-?[0-9]{1,3})?$",
            Pattern.CASE_INSENSITIVE);

    public static Optional<ImageKind> imageKind(String typeToken) {
        if (typeToken == null) {
            return Optional.empty();
        }
        Matcher m = IMAGE_TYPE.matcher(typeToken.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String base = m.group(1).toLowerCase(Locale.ROOT);
        if (base.startsWith("desktop")) return Optional.of(ImageKind.DESKTOP);
        if (base.startsWith("mobile")) return Optional.of(ImageKind.MOBILE);
        if (base.startsWith("logo")) return Optional.of(ImageKind.LOGO);
        if (base.startsWith("location")) return Optional.of(ImageKind.LOCATION);
        if (base.startsWith("thumbnail")) return Optional.of(ImageKind.THUMBNAIL);
        return Optional.of(ImageKind.GALLERY);
    }

    /** Hint for a rejected token, e.g. "ProjectLogo" → "Logo". */
    public static String imageTypeHint(String typeToken) {
        String t = typeToken == null ? "" : typeToken.toLowerCase(Locale.ROOT);
        if (t.contains("logo")) return "Logo";
        if (t.contains("thumb")) return "Thumbnail";
        if (t.contains("location") || t.contains("map")) return "Location";
        if (t.contains("desktop")) return "Desktop-1";
        if (t.contains("mobile")) return "Mobile-1";
        return "Gallery-1";
    }

    /** Same as the existing importer's {@code sanitizeImageType}. */
    public static String sanitizeImageType(String imageType) {
        return imageType.replaceAll("[^a-zA-Z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Same as the existing importer's {@code generateAltTag}. */
    public static String altTag(String projectName, String imageType) {
        String humanType = imageType.replace("-", " ").replace("_", " ");
        if (projectName == null || projectName.trim().isEmpty()) {
            return humanType;
        }
        return projectName.trim() + " - " + humanType;
    }

    /** Project key → name guess, as {@code resolveProjectFromZipKey} does ({@code -} → space). */
    public static String zipKeyToName(String projectKey) {
        return projectKey == null ? "" : projectKey.replace("-", " ").trim();
    }

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
}
