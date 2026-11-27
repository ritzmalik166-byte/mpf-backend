package com.mypropertyfact.estate.integration.claude.spec;

/**
 * Every column of the reference workbook {@code Property List-1 - Copy (1).xlsx} (Sheet1, A–AF),
 * in workbook order, with where its value goes. Headers are matched after
 * {@link com.mypropertyfact.estate.integration.claude.util.ClaudeText#normalizeHeader}.
 * FAQ columns ({@code Question n} / {@code Answer n}) are handled by {@link ClaudeImporterSpec}.
 */
public enum ClaudeColumn {

    ID("ID", Kind.REFERENCE_ONLY, false, 100,
            "Not stored on the project. Kept in the job result as sourceReference. Never used to find or update a project."),
    BUILDER_NAME("BUILDER NAME", Kind.FIELD, true, 255,
            "projects.builder_id → existing builder (case/space-insensitive), else a new builder is created (warning)"),
    PROJECT_NAME("PROJECT NAME", Kind.FIELD, true, 255,
            "projects.project_name; projects.slugurl = generateSlug(name)"),
    PROPERTY_TYPE_NAME("PROPERTY TYPE NAME", Kind.FIELD, true, 255,
            "projects.property_type → existing project_types row (must exist)"),
    PROJECT_STATUS_NAME("PROJECT STATUS NAME", Kind.FIELD, true, 255,
            "projects.project_status_id → existing project_status row (must exist)"),
    PROJECT_CONFIGURATION("PROJECT CONFIGURATION", Kind.FIELD, false, 255,
            "projects.project_configuration + floor_plans rows parsed from \"3 BHK-1395 sq.ft, 4 BHK-1744 sq.ft\""),
    PROJECT_LOCALITY("PROJECT LOCALITY", Kind.FIELD, false, 255, "projects.project_locality"),
    CITY_NAME("CITY NAME", Kind.FIELD, true, 255, "projects.city_Id → existing cities row (must exist, must belong to STATE NAME)"),
    STATE_NAME("STATE NAME", Kind.FIELD, true, 255, "projects.state_id → existing state row (must exist, must belong to COUNTRY NAME)"),
    COUNTRY_NAME("COUNTRY NAME", Kind.FIELD, true, 255, "validation only → existing country row (must exist)"),
    RERA_NO("RERA NO", Kind.FIELD, false, 255, "projects.rera_no (also a duplicate-detection key)"),
    RERA_WEBSITE("RERA WEBSITE", Kind.FIELD, false, 255, "projects.rera_website"),
    IVR_NO("IVR NO", Kind.FIELD, false, 255, "projects.ivr_no (numeric cells are written without decimals)"),
    PROJECT_PRICE("PROJECT PRICE", Kind.FIELD, false, 255, "projects.project_price (stored as text, exactly as the existing importer does)"),
    META_TITLE("META TITLE", Kind.FIELD, false, 255, "projects.meta_title"),
    META_DESCRIPTION("META DESCRIPTION", Kind.FIELD, false, 0, "projects.meta_description"),
    META_KEYWORD("META KEYWORD", Kind.FIELD, false, 0, "projects.meta_keyword"),
    AMENITY_DESCRIPTION("AMENITY DESCRIPTION", Kind.FIELD, false, 0, "projects.amenity_desc"),
    LOCATION_DESCRIPTION("LOCATION DESCRIPTION", Kind.FIELD, false, 0, "projects.location_desc"),
    FLOOR_PLAN_DESCRIPTION("FLOOR PLAN DESCRIPTION", Kind.FIELD, false, 0, "projects.floor_plan_desc"),
    BUILDER_DESCRIPTION("BUILDER DESCRIPTION", Kind.NEW_BUILDER_ONLY, false, 0,
            "builders.builder_desc ONLY when this import creates the builder. For an existing builder it is never written; the value is kept in the job result (unappliedFields)."),
    PROJECT_AMENITIES("PROJECT AMENITIES", Kind.FIELD, false, 0,
            "project_amenities links to existing amenity rows. Separators _ | , ; '-' is read as a space. Every token must match."),
    LOCATION_ADVANTAGE("LOCATION ADVANTAGE", Kind.FIELD, false, 0,
            "location_benefits rows from \"Name-Words-4.2-Km\" or \"Name (4.2 Km)\" segments. Text that cannot be parsed is reported and kept in the job result."),
    PROJECT_WALKTHROUGH_DESCRIPTION("PROJECT WALKTHROUGH DESCRIPTION", Kind.FIELD, false, 0, "project_walkthrough.walkthrough_desc"),
    /** Read by the existing importer, not present in the reference workbook. Supported for compatibility. */
    PROJECT_ABOUT_SHORT_DESCRIPTION("PROJECT ABOUT SHORT DESCRIPTION", Kind.FIELD, false, 0, "projects_about.long_desc");

    public enum Kind {
        FIELD,
        REFERENCE_ONLY,
        NEW_BUILDER_ONLY
    }

    private final String header;
    private final Kind kind;
    private final boolean required;
    /** Column length limit (VARCHAR 255), 0 = TEXT/LOB. */
    private final int maxLength;
    private final String target;

    ClaudeColumn(String header, Kind kind, boolean required, int maxLength, String target) {
        this.header = header;
        this.kind = kind;
        this.required = required;
        this.maxLength = maxLength;
        this.target = target;
    }

    public String header() {
        return header;
    }

    public Kind kind() {
        return kind;
    }

    public boolean required() {
        return required;
    }

    public int maxLength() {
        return maxLength;
    }

    public String target() {
        return target;
    }
}
