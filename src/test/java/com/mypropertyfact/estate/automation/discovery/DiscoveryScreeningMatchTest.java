package com.mypropertyfact.estate.automation.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Matching added during backend integration (the delivered patch compared RERA strings exactly). */
class DiscoveryScreeningMatchTest {

    private static DiscoveryScreeningJob.ExistingProject project(int id, String name, String rera, String builder, String city, boolean published) {
        return new DiscoveryScreeningJob.ExistingProject(id, name, rera, name == null ? "" : name.toLowerCase().replace(' ', '-'), builder, city, published);
    }

    private static DiscoveryCandidate lead(String name, String builder, String rera) {
        return new DiscoveryCandidate("https://www.magicbricks.com/x", name, builder, "Ghaziabad", "New Launched", rera, "", "");
    }

    @Test
    void reraKeyIgnoresTheDateSuffixUsedInSomeRows() {
        assertThat(DiscoveryScreeningRules.reraKey("UPRERAPRJ844382/02/2026")).isEqualTo("UPRERAPRJ844382");
        assertThat(DiscoveryScreeningRules.reraKey("upreraprj844382")).isEqualTo("UPRERAPRJ844382");
        assertThat(DiscoveryScreeningRules.reraKey(" UPRERAPRJ 844382 ")).isEqualTo("UPRERAPRJ844382");
        assertThat(DiscoveryScreeningRules.reraKey("")).isEmpty();
        assertThat(DiscoveryScreeningRules.reraKey(null)).isEmpty();
        assertThat(DiscoveryScreeningRules.reraKey("HRERA-PKL-123")).isEqualTo("hrerapkl123");
    }

    @Test
    void reraMatchAcrossFormatsIsAnExistingProjectMatchIncludingDrafts() {
        var draft = project(1394, "Metro Suites Glitz", "UPRERAPRJ929781", "METRO SUITES HOMES LLP", "Ghaziabad", false);
        var m = DiscoveryScreeningJob.findMatches(lead("Totally Different Name", "", "UPRERAPRJ929781/01/2026"), List.of(draft));
        assertThat(m).hasSize(1);
        assertThat(m.get(0).matchType()).isEqualTo("RERA");
        assertThat(m.get(0).reason()).isEqualTo("EXISTING_PROJECT_MATCH");
        assertThat(m.get(0).project().published()).isFalse();
    }

    @Test
    void reraMatchesAnyNumberInAMultiRegistrationField() {
        // Real MPF rows: "UPRERAPRJ2303, UPRERAPRJ2863" and #99 with five registrations.
        assertThat(DiscoveryScreeningRules.reraKeys("UPRERAPRJ2303, UPRERAPRJ2863"))
                .containsExactly("UPRERAPRJ2303", "UPRERAPRJ2863");
        var multi = project(917, "Aditya Urban Homes", "UPRERAPRJ2303, UPRERAPRJ2863", "Aditya Group", "Ghaziabad", true);
        var m = DiscoveryScreeningJob.findMatches(lead("Some Phase 2", "", "UPRERAPRJ2863/04/2026"), List.of(multi));
        assertThat(m).extracting(DiscoveryScreeningJob.Match::matchType).containsExactly("RERA");
        assertThat(DiscoveryScreeningJob.findMatches(lead("X Y Z Tower", "", "UPRERAPRJ28"), List.of(multi))).isEmpty();
    }

    @Test
    void sameNameAndCityWithoutBuilderIsPossibleNotSilent() {
        // The delivered identity check required builder equality; supplied leads have a blank builder.
        var existing = project(10, "SG Orchard", "", "SG Estates", "Ghaziabad", true);
        var m = DiscoveryScreeningJob.findMatches(lead("S.G. Orchard", "", ""), List.of(existing));
        assertThat(m).extracting(DiscoveryScreeningJob.Match::matchType).containsExactly("NAME_CITY");
        assertThat(m.get(0).reason()).isEqualTo("POSSIBLE_EXISTING_PROJECT");
    }

    @Test
    void nameBuilderCityIsStrong() {
        var existing = project(11, "SG Orchard", "", "SG Estates", "Ghaziabad", true);
        var m = DiscoveryScreeningJob.findMatches(lead("SG Orchard", "SG Estates", ""), List.of(existing));
        assertThat(m.get(0).matchType()).isEqualTo("NAME_BUILDER_CITY");
        assertThat(m.get(0).reason()).isEqualTo("EXISTING_PROJECT_MATCH");
    }

    @Test
    void similarNameSameCityAndSameNameOtherCityAreFlagged() {
        var similar = project(12, "Aditya Elysia Pratap Vihar", "", "", "Ghaziabad", true);
        var otherCity = project(13, "Aditya Elysia", "", "", "Noida", true);
        var m = DiscoveryScreeningJob.findMatches(lead("Aditya Elysia", "", ""), List.of(similar, otherCity));
        assertThat(m).extracting(DiscoveryScreeningJob.Match::matchType)
                .containsExactlyInAnyOrder("SIMILAR_NAME_SAME_CITY", "SAME_NAME_OTHER_CITY");
    }

    @Test
    void unrelatedProjectsAndShortNamesDoNotMatch() {
        var a = project(14, "Gaur Cascades", "UPRERAPRJ1111", "Gaur", "Ghaziabad", true);
        var b = project(15, "SKA", "", "", "Ghaziabad", true);          // too short for "contains" similarity
        var c = project(16, "Imperia Heights", "", "", "Ghaziabad", true);
        var m = DiscoveryScreeningJob.findMatches(lead("SKA Imperia", "", "UPRERAPRJ900092/05/2026"), List.of(a, b, c));
        assertThat(m).isEmpty();
    }

    @Test
    void strongMatchesComeFirstAndAreCapped() {
        var many = new java.util.ArrayList<DiscoveryScreeningJob.ExistingProject>();
        for (int i = 0; i < 15; i++) many.add(project(100 + i, "Same Name", "", "", "Ghaziabad", true));
        many.add(project(999, "Other", "UPRERAPRJ5", "", "Ghaziabad", false));
        var m = DiscoveryScreeningJob.findMatches(lead("Same Name", "", "UPRERAPRJ5/01/2026"), many);
        assertThat(m).hasSize(DiscoveryScreeningJob.MAX_MATCHES_PER_CANDIDATE);
        assertThat(m.get(0).matchType()).isEqualTo("RERA");
    }

    @Test
    void missingStatusIsReportedAsMissing() {
        var c = new DiscoveryCandidate("https://www.99acres.com/x", "A", "B", "Ghaziabad", "", "UPRERAPRJ1", "", "");
        assertThat(DiscoveryScreeningRules.screen(c)).contains("PROJECT_STATUS_MISSING").doesNotContain("UNSUPPORTED_PROJECT_STATUS");
        var d = new DiscoveryCandidate("https://www.99acres.com/x", "A", "B", "Ghaziabad", "Sold Out", "UPRERAPRJ1", "", "");
        assertThat(DiscoveryScreeningRules.screen(d)).contains("UNSUPPORTED_PROJECT_STATUS");
    }
}
