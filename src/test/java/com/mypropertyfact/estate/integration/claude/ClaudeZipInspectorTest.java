package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeZipInspector;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeZipInspectorTest {

    @TempDir
    Path tmp;

    private ClaudeIntegrationProperties props;
    private ClaudeZipInspector inspector;

    @BeforeEach
    void setUp() {
        props = new ClaudeIntegrationProperties();
        inspector = new ClaudeZipInspector(props);
    }

    private ClaudeZipInspector.Result inspect(Map<String, byte[]> entries) throws Exception {
        return inspector.inspect(ClaudeTestFixtures.zip(tmp, "t" + System.nanoTime() + ".zip", entries));
    }

    @Test
    void acceptsValidImagesInSeveralProjectFolders() throws Exception {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("Victory-Ace/Victory-Ace_Desktop-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 2508, 1200));
        e.put("HI-Castle/HI-Castle_Logo_My-Property-Fact.png", ClaudeTestFixtures.image("png", 792, 203));
        e.put("__MACOSX/._junk", new byte[]{1, 2, 3});
        ClaudeZipInspector.Result r = inspect(e);
        assertThat(r.issues).noneMatch(i -> i.isError());
        assertThat(r.images).hasSize(2);
        assertThat(r.images.get(0).projectKey()).isEqualTo("Victory-Ace");
        assertThat(r.images.get(0).kind()).isEqualTo(ImageKind.DESKTOP);
        assertThat(r.images.get(0).width()).isEqualTo(2508);
        assertThat(r.images.get(1).kind()).isEqualTo(ImageKind.LOGO);
        assertThat(r.images.get(1).format()).isEqualTo("png");
    }

    @Test
    void rejectsZipSlipEntries() throws Exception {
        ClaudeZipInspector.Result r = inspect(Map.of("../../etc/A_Gallery-1_x.jpg", ClaudeTestFixtures.image("jpg", 200, 200)));
        assertThat(r.issues).anyMatch(i -> "ZIP_UNSAFE_PATH".equals(i.getCode()));
        assertThat(r.images).isEmpty();
    }

    @Test
    void rejectsDuplicateFileNamesAcrossFolders() throws Exception {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("a/A_Gallery-1_x.jpg", ClaudeTestFixtures.image("jpg", 200, 200));
        e.put("b/A_Gallery-1_x.jpg", ClaudeTestFixtures.image("jpg", 200, 200));
        assertThat(inspect(e).issues).anyMatch(i -> "ZIP_DUPLICATE_FILE_NAME".equals(i.getCode()));
    }

    @Test
    void rejectsNonImagesFakeImagesAndExtensionMismatch() throws Exception {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("A_Brochure_x.pdf", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        e.put("A_Gallery-1_x.jpg", "this is not an image, just text pretending".getBytes(StandardCharsets.US_ASCII));
        e.put("A_Gallery-2_x.jpg", ClaudeTestFixtures.image("png", 200, 200));
        ClaudeZipInspector.Result r = inspect(e);
        assertThat(r.issues).anyMatch(i -> "ZIP_UNEXPECTED_FILE".equals(i.getCode()));
        assertThat(r.issues).anyMatch(i -> "IMAGE_INVALID_CONTENT".equals(i.getCode()));
        assertThat(r.issues).anyMatch(i -> "IMAGE_EXTENSION_MISMATCH".equals(i.getCode()));
        assertThat(r.images).isEmpty();
    }

    @Test
    void rejectsTruncatedCorruptedImage() throws Exception {
        byte[] full = ClaudeTestFixtures.image("jpg", 600, 600);
        byte[] truncated = Arrays.copyOf(full, full.length / 2);
        ClaudeZipInspector.Result r = inspect(Map.of("A_Gallery-1_x.jpg", truncated));
        assertThat(r.issues).anyMatch(i -> "IMAGE_CORRUPTED".equals(i.getCode()));
    }

    @Test
    void rejectsTooSmallImages() throws Exception {
        ClaudeZipInspector.Result r = inspect(Map.of("A_Gallery-1_x.png", ClaudeTestFixtures.image("png", 40, 40)));
        assertThat(r.issues).anyMatch(i -> "IMAGE_TOO_SMALL".equals(i.getCode()));
    }

    @Test
    void detectsArchiveBombByRatioAndByExtractedSize() throws Exception {
        byte[] zeros = new byte[8 * 1024 * 1024];
        ClaudeZipInspector.Result ratio = inspect(Map.of("A_Gallery-1_x.png", zeros));
        assertThat(ratio.issues).anyMatch(i -> "ZIP_SUSPICIOUS_RATIO".equals(i.getCode()));

        props.setMaxImageBytes(1024 * 1024);
        ClaudeZipInspector.Result big = inspect(Map.of("A_Gallery-1_x.png", zeros));
        assertThat(big.issues).anyMatch(i -> "IMAGE_TOO_LARGE".equals(i.getCode()));
    }

    @Test
    void enforcesEntryLimit() throws Exception {
        props.setMaxZipEntries(2);
        Map<String, byte[]> e = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            e.put("A_Gallery-" + i + "_x.png", ClaudeTestFixtures.image("png", 200, 200));
        }
        assertThat(inspect(e).issues).anyMatch(i -> "ZIP_TOO_MANY_ENTRIES".equals(i.getCode()));
    }

    @Test
    void reportsUnreadableArchive() throws Exception {
        Path p = tmp.resolve("broken.zip");
        Files.write(p, "PK\u0003\u0004garbage".getBytes(StandardCharsets.ISO_8859_1));
        ClaudeZipInspector.Result r = inspector.inspect(p);
        assertThat(r.unreadable).isTrue();
        assertThat(r.issues).anyMatch(i -> "ZIP_UNREADABLE".equals(i.getCode()));
    }
}
