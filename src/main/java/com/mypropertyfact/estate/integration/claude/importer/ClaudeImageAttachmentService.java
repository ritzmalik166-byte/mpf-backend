package com.mypropertyfact.estate.integration.claude.importer;

import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.model.ClaudeZipImage;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeZipInspector;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImageStudioSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;

/**
 * Isolated image path for the connector. It does NOT call {@code ProjectExcelUploadService} (whose
 * image step is private and whose only public entry point also runs project creation/update).
 *
 * <p>Storage rule (MPF Image Studio):
 * <ul>
 *   <li>An image that already has its slot's approved size (desktop 2225x1065, mobile 2700x2700,
 *       location 815x815, thumbnail 400x400, logo 390x100), and every gallery image, is stored as
 *       its ORIGINAL: same pixels, dimensions and format (JPEG, PNG or WebP). Only metadata segments
 *       (EXIF incl. GPS, XMP, IPTC, comments) are removed, losslessly. The result is decoded again and
 *       must have the same dimensions before it is written.</li>
 *   <li>Anything else (an off-spec banner, a JPEG with a non-standard EXIF rotation, or a file the
 *       lossless path cannot handle) goes through the existing primitive
 *       {@link FileUtils#saveImageFromBytes}, which fits it into the importer's box, exactly as before.</li>
 * </ul>
 *
 * <p>Images are saved and their rows inserted in slot order (Desktop-1 day before Desktop-2 night,
 * Mobile-1 before Mobile-2, Gallery-1 before Gallery-2), whatever the ZIP entry order. Files go into
 * the same folder layout ({@code ${upload_dir}properties/<slug>/}) with the same naming and alt-tag
 * rules as before.
 *
 * <p>Images are only ever attached to a project that this job created and that is still a draft
 * (checked again inside {@link ClaudeImageRowWriter}). If the database insert fails, the files this
 * call wrote are deleted; no other file is ever deleted.
 */
@Slf4j
@Service
public class ClaudeImageAttachmentService {

    private final FileUtils fileUtils;
    private final ClaudeImageRowWriter rowWriter;
    private final ClaudeIntegrationProperties props;

    @Value("${upload_dir}")
    private String uploadDir;

    public ClaudeImageAttachmentService(FileUtils fileUtils, ClaudeImageRowWriter rowWriter, ClaudeIntegrationProperties props) {
        this.fileUtils = fileUtils;
        this.rowWriter = rowWriter;
        this.props = props;
    }

    public record AttachResult(int imported, List<String> errors) {
    }

    public AttachResult attach(String jobId, int projectId, String slug, String projectName,
                               List<ClaudeZipImage> images, ZipFile zip) {
        List<String> errors = new ArrayList<>();
        if (images.isEmpty()) {
            return new AttachResult(0, errors);
        }
        String dir = uploadDir + "properties/" + slug + "/";
        List<ClaudeZipImage> ordered = new ArrayList<>(images);
        ordered.sort(Comparator.comparingInt((ClaudeZipImage i) -> ClaudeImageStudioSpec.saveRank(i.kind(), i.typeToken()))
                .thenComparing(ClaudeZipImage::entryName));
        List<ClaudeImageRowWriter.StoredImage> stored = new ArrayList<>();
        for (ClaudeZipImage img : ordered) {
            try {
                byte[] data = ClaudeZipInspector.readEntry(zip, img.entryName(), props.getMaxImageBytes());
                String baseName = ClaudeImporterSpec.sanitizeImageType(img.typeToken()) + "_" + UUID.randomUUID().toString().substring(0, 8);
                String name = null;
                if (ClaudeImageStudioSpec.keepOriginal(img.kind(), img.width(), img.height())) {
                    name = saveOriginal(data, img, baseName, dir);
                    if (name == null) {
                        log.info("Claude job {}: {} not kept as original; using the resize path", jobId, img.fileName());
                    }
                }
                if (name == null) {
                    name = fileUtils.saveImageFromBytes(data, baseName, dir, img.kind().boxWidth, img.kind().boxHeight);
                }
                if (name == null) {
                    errors.add("Image " + img.fileName() + " could not be processed");
                    continue;
                }
                stored.add(new ClaudeImageRowWriter.StoredImage(img.kind(), name,
                        ClaudeImporterSpec.altTag(projectName, img.typeToken())));
            } catch (Exception e) {
                errors.add("Image " + img.fileName() + " could not be read: " + e.getClass().getSimpleName());
            }
        }
        if (stored.isEmpty()) {
            return new AttachResult(0, errors);
        }
        try {
            int n = rowWriter.insert(jobId, projectId, slug, stored);
            return new AttachResult(n, errors);
        } catch (Exception e) {
            log.warn("Claude job {}: image rows for project {} failed: {}", jobId, projectId, e.getMessage());
            for (ClaudeImageRowWriter.StoredImage s : stored) {
                File f = new File(dir, s.storedName());
                if (f.isFile() && !f.delete()) {
                    log.warn("Claude job {}: could not remove unrecorded file {}", jobId, s.storedName());
                }
            }
            errors.add("Image records could not be saved; " + stored.size() + " written file(s) were removed: " + e.getMessage());
            return new AttachResult(0, errors);
        }
    }

    /**
     * Writes the image unchanged except for removed metadata. Returns the stored file name, or
     * {@code null} when the original cannot be kept safely (the caller then uses the resize path).
     */
    String saveOriginal(byte[] data, ClaudeZipImage img, String baseName, String dir) {
        String format = sniff(data);
        if (format == null || !format.equals(img.format())) {
            return null;
        }
        if ("jpg".equals(format) && ClaudeImageMetadata.jpegOrientation(data) != 1) {
            return null;
        }
        byte[] clean;
        try {
            clean = ClaudeImageMetadata.strip(format, data);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            BufferedImage check = ImageIO.read(new ByteArrayInputStream(clean));
            if (check == null || check.getWidth() != img.width() || check.getHeight() != img.height()) {
                return null;
            }
            Path folder = Files.createDirectories(Paths.get(dir)).toAbsolutePath().normalize();
            String fileName = System.currentTimeMillis() + "_" + baseName + "." + format;
            Path target = folder.resolve(fileName).normalize();
            if (!target.getParent().equals(folder)) {
                return null;
            }
            Files.write(target, clean, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return fileName;
        } catch (Exception e) {
            log.info("Original image {} could not be stored unchanged: {}", img.fileName(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static String sniff(byte[] d) {
        if (d.length >= 12 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
