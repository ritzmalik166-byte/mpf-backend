package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeImageAttachmentService;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeImageMetadata;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeImageRowWriter;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeImageRowWriter.StoredImage;
import com.mypropertyfact.estate.integration.claude.model.ClaudeZipImage;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeZipInspector;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImageStudioSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Image storage of the connector, without a database: the real inspector reads a ZIP, the real
 * attachment service writes into a temporary upload_dir, and the row writer (draft/claim checks and
 * the DB insert) is a mock that records what would be inserted, in order.
 */
class ClaudeImageStorageTest {

    @TempDir
    Path tmp;

    private ClaudeZipInspector inspector;
    private ClaudeImageRowWriter rowWriter;
    private ClaudeImageAttachmentService service;
    private Path uploadDir;

    @BeforeEach
    void setUp() throws Exception {
        ClaudeIntegrationProperties props = new ClaudeIntegrationProperties();
        inspector = new ClaudeZipInspector(props);
        rowWriter = mock(ClaudeImageRowWriter.class);
        when(rowWriter.insert(anyString(), anyInt(), anyString(), anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(3)).size());
        service = new ClaudeImageAttachmentService(new FileUtils(), rowWriter, props);
        uploadDir = Files.createDirectories(tmp.resolve("upload"));
        ReflectionTestUtils.setField(service, "uploadDir", uploadDir.toString() + "/");
    }

    private record Run(List<StoredImage> stored, ClaudeImageAttachmentService.AttachResult result, Map<String, byte[]> sources) {
    }

    @SuppressWarnings("unchecked")
    private Run attach(Path zipPath) throws Exception {
        ClaudeZipInspector.Result r = inspector.inspect(zipPath);
        assertThat(r.issues).noneMatch(i -> i.isError());
        Map<String, byte[]> sources = new LinkedHashMap<>();
        ClaudeImageAttachmentService.AttachResult res;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            for (ClaudeZipImage img : r.images) {
                sources.put(img.typeToken(), zip.getInputStream(zip.getEntry(img.entryName())).readAllBytes());
            }
            res = service.attach("job-1", 77, "metro-suites-glitz", "Metro Suites Glitz", r.images, zip);
        }
        ArgumentCaptor<List<StoredImage>> cap = ArgumentCaptor.forClass(List.class);
        verify(rowWriter).insert(eq("job-1"), eq(77), eq("metro-suites-glitz"), cap.capture());
        return new Run(cap.getValue(), res, sources);
    }

    private Path stored(StoredImage s) {
        return uploadDir.resolve("properties").resolve("metro-suites-glitz").resolve(s.storedName());
    }

    /** Photo-like noisy image, so it compresses like a real photo (a flat colour trips the ZIP bomb guard). */
    private static byte[] photo(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(w * 31L + h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg".equals(format) ? "jpg" : format, o);
        return o.toByteArray();
    }

    private static int[] dims(byte[] b) throws Exception {
        BufferedImage i = ImageIO.read(new ByteArrayInputStream(b));
        return new int[]{i.getWidth(), i.getHeight()};
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = ClaudeImageStorageTest.class.getResourceAsStream("/claude/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String token(StoredImage s) {
        // stored name: <millis>_<token>_<8 hex>.<ext>
        String[] p = s.storedName().split("_");
        return p[1];
    }

    private static boolean contains(byte[] hay, String needle) {
        return new String(hay, StandardCharsets.ISO_8859_1).contains(needle);
    }

    /* ------------------------------------------------------------------ synthetic package */

    @Test
    void specSizedImagesAndGalleryOriginalsAreStoredUnchangedInSlotOrder() throws Exception {
        String k = "Metro-Suites-Glitz/Metro-Suites-Glitz_";
        Map<String, byte[]> e = new LinkedHashMap<>(); // night first, gallery reversed
        e.put(k + "Desktop-2_My-Property-Fact.jpg", photo("jpg", 2225, 1065));
        e.put(k + "Desktop-1_My-Property-Fact.jpg", photo("jpg", 2225, 1065));
        e.put(k + "Mobile-2_My-Property-Fact.jpg", photo("jpg", 2700, 2700));
        e.put(k + "Mobile-1_My-Property-Fact.jpg", photo("jpg", 2700, 2700));
        e.put(k + "Gallery-2_My-Property-Fact.webp", resource("gallery-765x1020-exif.webp"));
        e.put(k + "Gallery-1_My-Property-Fact.jpg", photo("jpg", 1800, 1800));
        e.put(k + "Thumbnail_My-Property-Fact.jpg", resource("thumb-400x400-exif.jpg"));
        e.put(k + "Logo_My-Property-Fact.png", photo("png", 390, 100));
        e.put(k + "Location_My-Property-Fact.jpg", photo("jpg", 815, 815));
        Run run = attach(ClaudeTestFixtures.zip(tmp, "synthetic.zip", e));

        assertThat(run.result().imported()).isEqualTo(9);
        assertThat(run.result().errors()).isEmpty();
        assertThat(run.stored()).extracting(ClaudeImageStorageTest::token)
                .containsExactly("desktop-1", "desktop-2", "mobile-1", "mobile-2", "logo", "location", "thumbnail", "gallery-1", "gallery-2");
        for (StoredImage s : run.stored()) {
            String tok = token(s);
            byte[] src = run.sources().entrySet().stream().filter(x -> x.getKey().equalsIgnoreCase(tok)).findFirst().orElseThrow().getValue();
            byte[] out = Files.readAllBytes(stored(s));
            assertThat(dims(out)).as(tok + " dimensions").containsExactly(dims(src));
            String srcExt = src[0] == 'R' ? "webp" : (src[0] & 0xFF) == 0x89 ? "png" : "jpg";
            assertThat(s.storedName()).as(tok + " format").endsWith("." + srcExt);
            if (!"thumbnail".equals(tok) && !"gallery-2".equals(tok)) {
                assertThat(out).as(tok + " bytes unchanged").isEqualTo(src);
            }
        }
        assertThat(run.stored()).filteredOn(s -> s.kind() == ImageKind.LOGO).extracting(StoredImage::altTag).containsExactly("Metro Suites Glitz - Logo");
        assertThat(run.stored()).filteredOn(s -> s.kind() == ImageKind.LOCATION).extracting(StoredImage::altTag).containsExactly("Metro Suites Glitz - Location");
    }

    @Test
    void metadataIsRemovedWithoutChangingPixelsOrFormat() throws Exception {
        String k = "Metro-Suites-Glitz/Metro-Suites-Glitz_";
        byte[] jpg = resource("thumb-400x400-exif.jpg");
        byte[] webp = resource("gallery-765x1020-exif.webp");
        assertThat(contains(jpg, "secret-location")).isTrue();
        assertThat(contains(webp, "secret-location")).isTrue();
        Run run = attach(ClaudeTestFixtures.zip(tmp, "meta.zip", Map.of(
                k + "Thumbnail_My-Property-Fact.jpg", jpg,
                k + "Gallery-1_My-Property-Fact.webp", webp)));
        for (StoredImage s : run.stored()) {
            byte[] out = Files.readAllBytes(stored(s));
            assertThat(contains(out, "secret-location")).as(s.storedName()).isFalse();
            assertThat(contains(out, "Exif")).as(s.storedName()).isFalse();
        }
        StoredImage thumb = run.stored().stream().filter(s -> s.kind() == ImageKind.THUMBNAIL).findFirst().orElseThrow();
        StoredImage gal = run.stored().stream().filter(s -> s.kind() == ImageKind.GALLERY).findFirst().orElseThrow();
        assertThat(thumb.storedName()).endsWith(".jpg");
        assertThat(gal.storedName()).endsWith(".webp");
        // identical decoded pixels
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(jpg));
        BufferedImage b = ImageIO.read(stored(thumb).toFile());
        assertThat(b.getRGB(0, 0, 400, 400, null, 0, 400)).isEqualTo(a.getRGB(0, 0, 400, 400, null, 0, 400));
        assertThat(dims(Files.readAllBytes(stored(gal)))).containsExactly(765, 1020);
    }

    @Test
    void offSpecImagesStillGoThroughTheExistingResizePath() throws Exception {
        String k = "Metro-Suites-Glitz/Metro-Suites-Glitz_";
        Run run = attach(ClaudeTestFixtures.zip(tmp, "offspec.zip", Map.of(
                k + "Mobile-1_My-Property-Fact.jpg", photo("jpg", 2380, 2380),
                k + "Location_My-Property-Fact.jpg", photo("jpg", 815, 812))));
        for (StoredImage s : run.stored()) {
            int[] d = dims(Files.readAllBytes(stored(s)));
            if (s.kind() == ImageKind.MOBILE) {
                assertThat(d).containsExactly(800, 800);
            } else {
                assertThat(d).containsExactly(803, 800);
            }
        }
    }

    @Test
    void rotatedJpegIsNotKeptAsOriginal() throws Exception {
        byte[] rotated = resource("thumb-400x400-rotated.jpg");
        assertThat(ClaudeImageMetadata.jpegOrientation(rotated)).isEqualTo(6);
        Run run = attach(ClaudeTestFixtures.zip(tmp, "rot.zip", Map.of(
                "Metro-Suites-Glitz/Metro-Suites-Glitz_Thumbnail_My-Property-Fact.jpg", rotated)));
        assertThat(dims(Files.readAllBytes(stored(run.stored().get(0))))).containsExactly(800, 800);
    }

    @Test
    void pngTextChunksAreDropped() throws Exception {
        byte[] png = photo("png", 390, 100);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(png, 0, 33); // signature + IHDR
        byte[] text = "Commentsecret-location".getBytes(StandardCharsets.ISO_8859_1);
        o.write(new byte[]{0, 0, 0, (byte) (text.length)});
        o.write("tEXt".getBytes(StandardCharsets.ISO_8859_1));
        o.write(text);
        o.write(new byte[]{0, 0, 0, 0}); // CRC (not checked by the stripper; chunk is dropped)
        o.write(png, 33, png.length - 33);
        byte[] stripped = ClaudeImageMetadata.strip("png", o.toByteArray());
        assertThat(contains(stripped, "secret-location")).isFalse();
        assertThat(stripped).isEqualTo(png);
    }

    @Test
    void specTable() {
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.DESKTOP, 2225, 1065)).isTrue();
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.MOBILE, 2700, 2700)).isTrue();
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.MOBILE, 2380, 2380)).isFalse();
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.LOCATION, 815, 815)).isTrue();
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.LOCATION, 815, 812)).isFalse();
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.THUMBNAIL, 400, 400)).isTrue();
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.LOGO, 390, 100)).isTrue();
        assertThat(ClaudeImageStudioSpec.keepOriginal(ImageKind.GALLERY, 123, 4567)).isTrue();
        assertThat(ClaudeImageStudioSpec.saveRank(ImageKind.DESKTOP, "Desktop-1")).isLessThan(ClaudeImageStudioSpec.saveRank(ImageKind.DESKTOP, "Desktop-2"));
        assertThat(ClaudeImageStudioSpec.saveRank(ImageKind.GALLERY, "Gallery-2")).isLessThan(ClaudeImageStudioSpec.saveRank(ImageKind.GALLERY, "Gallery-10"));
    }

    /* ------------------------------------------------------------------ Metro Suites Glitz package (optional) */

    /** Runs when -Dclaude.metroZip=<canonical 10-image ZIP> is given; prints a per-image table. */
    @Test
    void metroSuitesGlitzPackageKeepsApprovedDimensionsAndFormats() throws Exception {
        String p = System.getProperty("claude.metroZip");
        assumeTrue(p != null && Files.isRegularFile(Paths.get(p)), "claude.metroZip not set");
        Run run = attach(Paths.get(p));
        assertThat(run.result().imported()).isEqualTo(10);
        assertThat(run.result().errors()).isEmpty();
        assertThat(run.stored()).extracting(ClaudeImageStorageTest::token).containsExactly(
                "desktop-1", "desktop-2", "mobile-1", "mobile-2", "logo", "location", "thumbnail", "gallery-1", "gallery-2", "gallery-3");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        StringBuilder table = new StringBuilder("\nMETRO_STORAGE_REPORT order | slot | source | stored | format | bytes src->stored | identical | alt\n");
        int order = 0;
        for (StoredImage s : run.stored()) {
            String tok = token(s);
            byte[] src = run.sources().entrySet().stream().filter(x -> x.getKey().equalsIgnoreCase(tok)).findFirst().orElseThrow().getValue();
            byte[] out = Files.readAllBytes(stored(s));
            int[] ds = dims(src);
            int[] dout = dims(out);
            assertThat(dout).as(tok).containsExactly(ds);
            String srcExt = src[0] == 'R' ? "webp" : (src[0] & 0xFF) == 0x89 ? "png" : "jpg";
            assertThat(s.storedName()).as(tok).endsWith("." + srcExt);
            int[] spec = ClaudeImageStudioSpec.size(s.kind());
            if (spec != null) {
                assertThat(dout).as(tok + " Image Studio size").containsExactly(spec);
            }
            boolean same = HexFormat.of().formatHex(sha.digest(src)).equals(HexFormat.of().formatHex(sha.digest(out)));
            table.append(String.format("METRO_STORAGE_REPORT %d | %s | %dx%d | %dx%d | %s -> %s | %d -> %d | %s | %s%n", ++order, tok,
                    ds[0], ds[1], dout[0], dout[1], srcExt, s.storedName().substring(s.storedName().lastIndexOf('.') + 1),
                    src.length, out.length, same ? "byte-identical" : "metadata removed", s.altTag()));
        }
        System.out.println(table);
    }
}
