package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.integration.claude.spec.ClaudeColumn;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeImporterSpecTest {

    @Test
    void everyReferenceHeaderIsKnownAfterNormalisation() {
        assertThat(ClaudeImporterSpec.REFERENCE_WORKBOOK_HEADERS).hasSize(32);
        for (String h : ClaudeImporterSpec.REFERENCE_WORKBOOK_HEADERS) {
            assertThat(ClaudeImporterSpec.isKnownHeader(ClaudeText.normalizeHeader(h))).as(h).isTrue();
        }
    }

    @Test
    void headerNormalisationHandlesLeadingSpaceCaseAndInnerWhitespace() {
        assertThat(ClaudeText.normalizeHeader(" PROJECT CONFIGURATION")).isEqualTo("PROJECT CONFIGURATION");
        assertThat(ClaudeText.normalizeHeader("PROJECT Amenities")).isEqualTo("PROJECT AMENITIES");
        assertThat(ClaudeText.normalizeHeader("  project   status\tname ")).isEqualTo("PROJECT STATUS NAME");
        assertThat(ClaudeImporterSpec.column(ClaudeText.normalizeHeader(" PROJECT CONFIGURATION")))
                .contains(ClaudeColumn.PROJECT_CONFIGURATION);
        assertThat(ClaudeImporterSpec.column("PROJECT AMENITIES")).contains(ClaudeColumn.PROJECT_AMENITIES);
    }

    @Test
    void idIsReferenceOnlyAndBuilderDescriptionIsNewBuilderOnly() {
        assertThat(ClaudeColumn.ID.kind()).isEqualTo(ClaudeColumn.Kind.REFERENCE_ONLY);
        assertThat(ClaudeColumn.BUILDER_DESCRIPTION.kind()).isEqualTo(ClaudeColumn.Kind.NEW_BUILDER_ONLY);
    }

    @Test
    void allFourFaqPairsAreRecognised() {
        for (int i = 1; i <= 4; i++) {
            assertThat(ClaudeImporterSpec.faqHeader("QUESTION " + i)).containsExactly("QUESTION", i);
            assertThat(ClaudeImporterSpec.faqHeader("ANSWER " + i)).containsExactly("ANSWER", i);
        }
        assertThat(ClaudeImporterSpec.faqHeader("QUESTION 21")).isNull();
        assertThat(ClaudeImporterSpec.faqHeader("QUESTION")).isNull();
    }

    @Test
    void configurationParsingMatchesExistingImporter() {
        var p = ClaudeImporterSpec.parseConfiguration("2 BHK-908 sq.ft, 3 BHK-899 sq.ft");
        assertThat(p.plans()).hasSize(2);
        assertThat(p.plans().get(0).planType()).isEqualTo("2 BHK");
        assertThat(p.plans().get(0).areaSqft()).isEqualTo(908.0);
        assertThat(p.plans().get(0).areaSqmt()).isEqualTo(84.36);
        assertThat(p.unparsedSegments()).isEmpty();

        var studio = ClaudeImporterSpec.parseConfiguration("Studo Apartment-525 sq.ft");
        assertThat(studio.plans()).extracting(ClaudeImporterSpec.FloorPlanSpec::planType).containsExactly("Studo Apartment");

        var plots = ClaudeImporterSpec.parseConfiguration("Plot-258 sq.ft, Plot-1074 sq.ft");
        assertThat(plots.plans()).hasSize(2);

        var bad = ClaudeImporterSpec.parseConfiguration("Penthouse, 3 BHK-");
        assertThat(bad.plans()).isEmpty();
        assertThat(bad.unparsedSegments()).containsExactly("Penthouse", "3 BHK-");
    }

    @Test
    void locationAdvantageSupportsBothExistingFormatsAndReportsProse() {
        var ok = ClaudeImporterSpec.parseLocationAdvantage("Hyatt-Regency-Gurugram-4.2-Km_Cyber Hub (8 Km)");
        assertThat(ok.benefits()).hasSize(2);
        assertThat(ok.benefits().get(0).name()).isEqualTo("Hyatt Regency Gurugram");
        assertThat(ok.benefits().get(0).distance()).isEqualTo("4.2 Km");
        assertThat(ok.benefits().get(1).name()).isEqualTo("Cyber Hub");
        assertThat(ok.unparsedSegments()).isEmpty();

        var prose = ClaudeImporterSpec.parseLocationAdvantage(
                "Located in Crossings Republik, Ghaziabad; access to NH-24 and Delhi-Meerut Expressway");
        assertThat(prose.benefits()).isEmpty();
        assertThat(prose.unparsedSegments()).isNotEmpty();
    }

    @Test
    void imageTypesAreStrictButMapToTheExistingSlots() {
        assertThat(ClaudeImporterSpec.imageKind("Logo")).contains(ImageKind.LOGO);
        assertThat(ClaudeImporterSpec.imageKind("Thumbnail")).contains(ImageKind.THUMBNAIL);
        assertThat(ClaudeImporterSpec.imageKind("LocationMap")).contains(ImageKind.LOCATION);
        assertThat(ClaudeImporterSpec.imageKind("Location")).contains(ImageKind.LOCATION);
        assertThat(ClaudeImporterSpec.imageKind("Desktop-1")).contains(ImageKind.DESKTOP);
        assertThat(ClaudeImporterSpec.imageKind("DesktopBanner")).contains(ImageKind.DESKTOP);
        assertThat(ClaudeImporterSpec.imageKind("mobile-2")).contains(ImageKind.MOBILE);
        assertThat(ClaudeImporterSpec.imageKind("Gallery1")).contains(ImageKind.GALLERY);
        assertThat(ClaudeImporterSpec.imageKind("Gallery-12")).contains(ImageKind.GALLERY);
        // The existing importer would silently save these as gallery images; the connector rejects them.
        assertThat(ClaudeImporterSpec.imageKind("ProjectLogo")).isEqualTo(Optional.empty());
        assertThat(ClaudeImporterSpec.imageKind("ProjectThumbnail")).isEmpty();
        assertThat(ClaudeImporterSpec.imageKind("Opening-1")).isEmpty();
        assertThat(ClaudeImporterSpec.imageTypeHint("ProjectLogo")).isEqualTo("Logo");
    }

    @Test
    void amenityKeyMatchesExistingHyphenRule() {
        assertThat(ClaudeText.amenityKey("24-x-7-Water-Supply")).isEqualTo(ClaudeText.amenityKey("24 x 7 Water Supply"));
        assertThat(ClaudeText.amenityKey("Wi-Fi-Connectivity")).isEqualTo(ClaudeText.amenityKey("Wi-fi Connectivity"));
        assertThat(ClaudeText.amenityKey("Rainwater-Harvesting")).isNotEqualTo(ClaudeText.amenityKey("Rain Water Harvesting"));
    }

    @Test
    void reraKeyIgnoresCaseAndWhitespace() {
        assertThat(ClaudeText.reraKey(" 95 of 2017 ")).isEqualTo("95OF2017");
    }

    @Test
    void altTagAndSanitisingAreIdenticalToExistingImporter() {
        assertThat(ClaudeImporterSpec.altTag("Victory Ace", "Gallery-2")).isEqualTo("Victory Ace - Gallery 2");
        assertThat(ClaudeImporterSpec.sanitizeImageType("Desktop 1!")).isEqualTo("desktop-1");
    }
}
