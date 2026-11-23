package com.mypropertyfact.estate.integration.claude.importer;

import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.model.ClaudeZipImage;
import com.mypropertyfact.estate.integration.claude.processor.ClaudeZipInspector;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;

/**
 * Isolated image path for the connector. It does NOT call {@code ProjectExcelUploadService} (whose
 * image step is private and whose only public entry point also runs project creation/update). It
 * reuses exactly one existing public primitive, {@link FileUtils#saveImageFromBytes}, so files are
 * resized and encoded identically to the admin importer, into the same folder layout
 * ({@code ${upload_dir}properties/<slug>/}) with the same naming and alt-tag rules.
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
        List<ClaudeImageRowWriter.StoredImage> stored = new ArrayList<>();
        for (ClaudeZipImage img : images) {
            try {
                byte[] data = ClaudeZipInspector.readEntry(zip, img.entryName(), props.getMaxImageBytes());
                String baseName = ClaudeImporterSpec.sanitizeImageType(img.typeToken()) + "_" + UUID.randomUUID().toString().substring(0, 8);
                String name = fileUtils.saveImageFromBytes(data, baseName, dir, img.kind().boxWidth, img.kind().boxHeight);
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
}
