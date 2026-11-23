package com.mypropertyfact.estate.integration.claude.model;

import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.FloorPlanSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.LocationBenefitSpec;

import java.util.*;

/**
 * Everything the draft importer needs for one Excel row, fully resolved during validation. The
 * importer never re-reads the workbook; it only writes what is in this plan.
 */
public class ClaudeRowPlan {

    public enum State {VALID, BLOCKED, DUPLICATE}

    public record Faq(String question, String answer) {
    }

    public final int excelRow;
    public final ClaudeParsedWorkbook.Row row;

    public String sourceReference;
    public String projectName;
    public String slug;

    public String builderName;
    public String builderSlug;
    public Integer builderId;
    public boolean newBuilder;
    public String builderDescription;

    public Integer cityId;
    public String cityName;
    public Integer stateId;
    public Integer countryId;
    public Integer projectStatusId;
    public Integer projectTypeId;

    public String reraNo;
    public String reraKey;

    /** Project scalar fields: setter name → value. Only non-blank values. */
    public final Map<String, String> projectFields = new LinkedHashMap<>();
    public final LinkedHashSet<Integer> amenityIds = new LinkedHashSet<>();
    public final List<String> amenityTitles = new ArrayList<>();
    public final List<FloorPlanSpec> floorPlans = new ArrayList<>();
    public final List<LocationBenefitSpec> locationBenefits = new ArrayList<>();
    public final List<Faq> faqs = new ArrayList<>();
    public String walkthroughDescription;
    public String aboutDescription;

    public final List<ClaudeZipImage> images = new ArrayList<>();

    public Integer existingProjectId;
    public final List<String> duplicateReasons = new ArrayList<>();
    /** Claims left by an earlier Claude import whose project has since been deleted; released before import. */
    public final List<Long> staleClaimIds = new ArrayList<>();
    public final List<ClaudeValidationIssue> issues = new ArrayList<>();
    public final Map<String, String> unappliedFields = new LinkedHashMap<>();

    public ClaudeRowPlan(ClaudeParsedWorkbook.Row row) {
        this.row = row;
        this.excelRow = row.excelRow();
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(ClaudeValidationIssue::isError);
    }

    public State state() {
        if (hasErrors()) {
            return State.BLOCKED;
        }
        if (!duplicateReasons.isEmpty()) {
            return State.DUPLICATE;
        }
        return State.VALID;
    }

    public void error(String code, String column, String message, String value) {
        issues.add(ClaudeValidationIssue.error(code, message).row(excelRow).column(column).value(value));
    }

    public void warning(String code, String column, String message, String value) {
        issues.add(ClaudeValidationIssue.warning(code, message).row(excelRow).column(column).value(value));
    }

    /** Identity keys claimed in claude_integration_project_claim when the draft is created. */
    public List<String> claimKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("SLUG:" + slug);
        if (reraKey != null && !reraKey.isEmpty()) {
            keys.add("RERA:" + reraKey);
        }
        keys.add("NBC:" + nameBuilderCityKey());
        return keys;
    }

    public String nameBuilderCityKey() {
        return com.mypropertyfact.estate.integration.claude.util.ClaudeText.lookupKey(projectName) + "|"
                + com.mypropertyfact.estate.integration.claude.util.ClaudeText.lookupKey(builderName) + "|"
                + (cityId != null ? cityId : com.mypropertyfact.estate.integration.claude.util.ClaudeText.lookupKey(cityName));
    }
}
