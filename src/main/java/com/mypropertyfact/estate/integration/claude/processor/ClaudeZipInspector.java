package com.mypropertyfact.estate.integration.claude.processor;

import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeValidationIssue;
import com.mypropertyfact.estate.integration.claude.model.ClaudeZipImage;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Inspects the image ZIP on disk without trusting any header: entry count, real (streamed) sizes,
 * compression ratio, path traversal, duplicate file names, image content (magic bytes + full
 * decode) and dimensions. Nothing is ever written using an entry's path.
 */
@Component
@RequiredArgsConstructor
public class ClaudeZipInspector {

    private final ClaudeIntegrationProperties props;

    private static final Set<String> JUNK_NAMES = Set.of(".ds_store", "thumbs.db", "desktop.ini");

    public static final class Result {
        public final List<ClaudeZipImage> images = new ArrayList<>();
        public final List<ClaudeValidationIssue> issues = new ArrayList<>();
        public int entries;
        public long extractedBytes;
        public boolean unreadable;
    }

    public Result inspect(Path zipPath) {
        Result result = new Result();
        try (ZipFile zip = open(zipPath)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            result.entries = entries.size();
            if (entries.size() > props.getMaxZipEntries()) {
                result.issues.add(ClaudeValidationIssue.error("ZIP_TOO_MANY_ENTRIES",
                        "The ZIP has " + entries.size() + " entries; the limit is " + props.getMaxZipEntries() + "."));
                return result;
            }
            Map<String, String> seenNames = new HashMap<>();
            for (ZipEntry entry : entries) {
                inspectEntry(zip, entry, seenNames, result);
                if (result.extractedBytes > props.getMaxExtractedBytes()) {
                    result.issues.add(ClaudeValidationIssue.error("ZIP_EXTRACTED_TOO_LARGE",
                            "Uncompressed content exceeds " + props.getMaxExtractedBytes() + " bytes (possible archive bomb)."));
                    return result;
                }
            }
        } catch (IOException | RuntimeException e) {
            result.unreadable = true;
            result.issues.add(ClaudeValidationIssue.error("ZIP_UNREADABLE",
                    "The image ZIP could not be read: " + ClaudeText.truncate(e.getClass().getSimpleName() + " " + e.getMessage(), 200)));
        }
        return result;
    }

    private static ZipFile open(Path path) throws IOException {
        try {
            return new ZipFile(path.toFile(), StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException e) {
            // Archives made on Windows often use CP437 names.
            return new ZipFile(path.toFile(), Charset.forName("IBM437"));
        }
    }

    private void inspectEntry(ZipFile zip, ZipEntry entry, Map<String, String> seenNames, Result result) throws IOException {
        String name = entry.getName();
        if (entry.isDirectory()) {
            if (isUnsafePath(name)) {
                result.issues.add(ClaudeValidationIssue.error("ZIP_UNSAFE_PATH", "Unsafe path in ZIP: " + name).value(name));
            }
            return;
        }
        if (isUnsafePath(name)) {
            result.issues.add(ClaudeValidationIssue.error("ZIP_UNSAFE_PATH",
                    "Entry path is absolute or contains '..' (ZIP Slip attempt): " + name).value(name));
            return;
        }
        String normalized = name.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        String folder = normalized.contains("/") ? normalized.substring(0, normalized.lastIndexOf('/')) : "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("__MACOSX/") || JUNK_NAMES.contains(lower) || fileName.startsWith("._")) {
            result.issues.add(ClaudeValidationIssue.info("ZIP_SYSTEM_FILE_IGNORED", "System file ignored: " + name).value(name));
            return;
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ClaudeImporterSpec.IMAGE_EXTENSIONS.contains(ext)) {
            result.issues.add(ClaudeValidationIssue.error("ZIP_UNEXPECTED_FILE",
                    "Only .jpg, .jpeg, .png and .webp images are allowed in the ZIP: " + name).value(name));
            return;
        }
        String previous = seenNames.putIfAbsent(lower, name);
        if (previous != null) {
            // The existing importer flattens folders, so same-named files would overwrite each other.
            result.issues.add(ClaudeValidationIssue.error("ZIP_DUPLICATE_FILE_NAME",
                    "File name \"" + fileName + "\" appears twice (" + previous + ", " + name + "). File names must be unique across folders.").value(name));
            return;
        }

        byte[] data = readBounded(zip, entry, result, name);
        if (data == null) {
            return;
        }
        long compressed = entry.getCompressedSize();
        if (compressed > 0 && data.length / compressed > props.getMaxCompressionRatio()) {
            result.issues.add(ClaudeValidationIssue.error("ZIP_SUSPICIOUS_RATIO",
                    "Entry " + name + " expands more than " + props.getMaxCompressionRatio() + "x (possible archive bomb).").value(name));
            return;
        }

        String format = sniffFormat(data);
        if (format == null) {
            result.issues.add(ClaudeValidationIssue.error("IMAGE_INVALID_CONTENT",
                    "Not a JPEG, PNG or WEBP image (content checked): " + name).value(name));
            return;
        }
        String extFormat = "jpeg".equals(ext) ? "jpg" : ext;
        if (!extFormat.equals(format)) {
            result.issues.add(ClaudeValidationIssue.error("IMAGE_EXTENSION_MISMATCH",
                    "File " + name + " is named ." + ext + " but its content is " + format.toUpperCase(Locale.ROOT) + ".").value(name));
            return;
        }
        int[] dims = readDimensions(data);
        if (dims == null) {
            result.issues.add(ClaudeValidationIssue.error("IMAGE_CORRUPTED", "Image cannot be decoded: " + name).value(name));
            return;
        }
        if ((long) dims[0] * dims[1] > props.getMaxImagePixels()) {
            result.issues.add(ClaudeValidationIssue.error("IMAGE_TOO_MANY_PIXELS",
                    "Image " + name + " is " + dims[0] + "x" + dims[1] + "; the limit is " + props.getMaxImagePixels() + " pixels.").value(name));
            return;
        }
        if (dims[0] < props.getMinImageSide() || dims[1] < props.getMinImageSide()) {
            result.issues.add(ClaudeValidationIssue.error("IMAGE_TOO_SMALL",
                    "Image " + name + " is " + dims[0] + "x" + dims[1] + "; each side must be at least " + props.getMinImageSide() + " px.").value(name));
            return;
        }
        if (!hasValidTrailer(format, data) || !fullyDecodes(data)) {
            result.issues.add(ClaudeValidationIssue.error("IMAGE_CORRUPTED", "Image data is truncated or corrupted: " + name).value(name));
            return;
        }

        String base = fileName.substring(0, dot);
        String[] parts = base.split("_");
        String projectKey = parts.length >= 2 ? parts[0].trim() : null;
        String typeToken = parts.length >= 2 ? parts[1].trim() : null;
        ImageKind kind = typeToken == null ? null : ClaudeImporterSpec.imageKind(typeToken).orElse(null);
        result.images.add(new ClaudeZipImage(name, folder, fileName, projectKey, typeToken, kind, format, dims[0], dims[1], data.length));
    }

    /** Reads an entry with a hard cap, counting real bytes (declared sizes are not trusted). */
    private byte[] readBounded(ZipFile zip, ZipEntry entry, Result result, String name) throws IOException {
        long cap = props.getMaxImageBytes();
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(entry.getSize(), 8192), 4 * 1024 * 1024));
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    result.extractedBytes += total;
                    result.issues.add(ClaudeValidationIssue.error("IMAGE_TOO_LARGE",
                            "Entry " + name + " is larger than " + cap + " bytes.").value(name));
                    return null;
                }
                out.write(buf, 0, n);
            }
            result.extractedBytes += total;
            return out.toByteArray();
        }
    }

    /** Re-reads one entry's bytes at import time, with the same cap. */
    public static byte[] readEntry(ZipFile zip, String entryName, long cap) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new IOException("Entry not found: " + entryName);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    throw new IOException("Entry exceeds size limit: " + entryName);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static ZipFile openZip(Path path) throws IOException {
        return open(path);
    }

    static boolean isUnsafePath(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            return true;
        }
        for (String seg : n.split("/")) {
            if (seg.equals("..")) {
                return true;
            }
        }
        return n.indexOf('\0') >= 0;
    }

    static String sniffFormat(byte[] d) {
        if (d == null || d.length < 12) {
            return null;
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 0x50 && d[2] == 0x4E && d[3] == 0x47) {
            return "png";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (d[0] == 0x52 && d[1] == 0x49 && d[2] == 0x46 && d[3] == 0x46
                && d[8] == 0x57 && d[9] == 0x45 && d[10] == 0x42 && d[11] == 0x50) {
            return "webp";
        }
        return null;
    }

    /** Width/height from the header only (cheap), so huge images are rejected before decoding. */
    static int[] readDimensions(byte[] data) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JPEG decoders silently pad truncated files with grey, so the end marker is checked too:
     * JPEG must end with FFD9 (trailing zero padding allowed), PNG with an IEND chunk.
     */
    static boolean hasValidTrailer(String format, byte[] d) {
        if ("jpg".equals(format)) {
            int end = d.length - 1;
            while (end > 1 && d[end] == 0) {
                end--;
            }
            return end > 1 && (d[end - 1] & 0xFF) == 0xFF && (d[end] & 0xFF) == 0xD9;
        }
        if ("png".equals(format)) {
            if (d.length < 12) {
                return false;
            }
            int i = d.length - 8;
            return d[i] == 'I' && d[i + 1] == 'E' && d[i + 2] == 'N' && d[i + 3] == 'D';
        }
        if ("webp".equals(format)) {
            long riffSize = (d[4] & 0xFFL) | (d[5] & 0xFFL) << 8 | (d[6] & 0xFFL) << 16 | (d[7] & 0xFFL) << 24;
            return riffSize + 8 <= d.length;
        }
        return false;
    }

    static boolean fullyDecodes(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            return img != null && img.getWidth() > 0;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }
}
