package com.mypropertyfact.estate.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.config.CacheNames;
import com.mypropertyfact.estate.dtos.BuilderLogoDto;
import com.mypropertyfact.estate.dtos.BuilderResponse;
import com.mypropertyfact.estate.dtos.BuilderDto;
import com.mypropertyfact.estate.dtos.BuilderWriteDto;
import com.mypropertyfact.estate.dtos.ProjectShortDetails;
import com.mypropertyfact.estate.entities.Builder;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.BuilderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class BuilderService {

    private static final int BUILDER_LOGO_MAX_W = 480;
    private static final int BUILDER_LOGO_MAX_H = 240;
    private static final int BUILDER_GALLERY_MAX_W = 1600;
    private static final int BUILDER_GALLERY_MAX_H = 1200;
    private static final int MAX_GALLERY_IMAGES_FROM_ZIP = 80;

    private final BuilderRepository builderRepository;
    private final ProjectCatalogService projectCatalogService;
    private final FileUtils fileUtils;
    private final ObjectMapper objectMapper;

    @Value("${upload_dir}")
    private String uploadDir;

    // Getting all builders
    @Cacheable(CacheNames.BUILDER_ALL)
    public BuilderResponse getAllBuilders() {
        return new BuilderResponse(builderRepository.findAllProjectedBy(Sort.by(Sort.Direction.ASC, "builderName")),
                new Response(1, "All builders fetched successfully...", 0));
    }

    @Cacheable(CacheNames.BUILDER_LOGOS)
    @Transactional(readOnly = true)
    public List<BuilderLogoDto> getBuildersWithLogos() {
        return List.copyOf(builderRepository.findAllWithLogo());
    }

    public Response addUpdateBuilder(BuilderWriteDto dto) {
        Response response = new Response();
        try {
            if (dto == null
                    || dto.getBuilderName() == null
                    || dto.getBuilderName().isBlank()) {
                return new Response(0, "Builder name is required !", 0);
            }
            String trimmedName = dto.getBuilderName().trim();
            Builder existsBuilder = this.builderRepository.findByBuilderName(trimmedName);
            if (existsBuilder != null && existsBuilder.getId() != dto.getId()) {
                response.setMessage("Builder already exists!");
                response.setIsSuccess(0);
                return response;
            }
            String finalSlug = slugFromBuilderName(trimmedName);
            if (dto.getId() != 0) {
                Optional<Builder> existing = this.builderRepository.findById(dto.getId());
                if (existing.isEmpty()) {
                    return new Response(0, "Builder not found for update.", 0);
                }
                Builder savedBuilder = existing.get();
                savedBuilder.setBuilderName(trimmedName);
                savedBuilder.setSlugUrl(finalSlug);
                savedBuilder.setBuilderDesc(dto.getBuilderDesc());
                savedBuilder.setMetaTitle(dto.getMetaTitle());
                savedBuilder.setMetaKeyword(dto.getMetaKeyword());
                savedBuilder.setMetaDesc(dto.getMetaDesc());
                savedBuilder.setUpdatedAt(LocalDateTime.now());
                this.builderRepository.save(savedBuilder);
                response.setIsSuccess(1);
                response.setMessage("Builder Updated Successfully...");
            } else {
                Builder builder = new Builder();
                builder.setBuilderName(trimmedName);
                builder.setSlugUrl(finalSlug);
                builder.setBuilderDesc(dto.getBuilderDesc());
                builder.setMetaTitle(dto.getMetaTitle());
                builder.setMetaKeyword(dto.getMetaKeyword());
                builder.setMetaDesc(dto.getMetaDesc());
                builder.setCreatedAt(LocalDateTime.now());
                builder.setUpdatedAt(LocalDateTime.now());
                this.builderRepository.save(builder);
                response.setMessage("Builder saved successfully...");
                response.setIsSuccess(1);
            }
        } catch (Exception e) {
            response.setMessage(e.getMessage() != null ? e.getMessage() : "Could not save builder.");
            response.setIsSuccess(0);
        }
        return response;
    }

    private static String slugFromBuilderName(String builderName) {
        String slugUrl = builderName.toLowerCase(Locale.ROOT);
        String[] words = slugUrl.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append("-");
            }
            result.append(words[i]);
        }
        return result.toString();
    }

    public Response deleteBuilder(int id) {
        Response response = new Response();
        try {
            this.builderRepository.deleteById(id);
            response.setIsSuccess(1);
            response.setMessage("Builder Deleted Successfully...");
        } catch (Exception e) {
            response.setMessage(e.getMessage());
            response.setIsSuccess(0);
        }
        return response;
    }

    @Transactional
    public BuilderDto getBySlug(String url) {
        Optional<Builder> dbBuilder = this.builderRepository.findBySlugUrl(url);
        List<ProjectShortDetails> projectDetailDtoList = projectCatalogService.findAllProjects().stream()
                .filter(project -> project.getBuilderSlug()
                        .equals(url))
                .collect(Collectors.toList());
        BuilderDto builderDto = new BuilderDto();
        dbBuilder.ifPresent(builder -> {
            builderDto.setId(builder.getId());
            builderDto.setBuilderName(builder.getBuilderName());
            builderDto.setSlugURL(builder.getSlugUrl());
            builderDto.setBuilderDescription(builder.getBuilderDesc());
            builderDto.setMetaTitle(builder.getMetaTitle());
            builderDto.setMetaKeywords(builder.getMetaKeyword());
            builderDto.setMetaDescription(builder.getMetaDesc());
            builderDto.setBuilderLogo(builder.getBuilderLogo());
            builderDto.setDeveloperGalleryImageNames(parseGalleryJson(builder.getDeveloperGalleryImagesJson()));
            builderDto.setProjectList(projectDetailDtoList);
        });
        return builderDto;
    }

    /**
     * Delete only the developer logo file for a builder (gallery is untouched).
     * Clears {@code builderLogo} in DB after removing the file from disk.
     */
    @Transactional
    public Response deleteDeveloperLogo(int builderId) {
        Response response = new Response();
        try {
            Optional<Builder> opt = builderRepository.findById(builderId);
            if (opt.isEmpty()) {
                response.setIsSuccess(0);
                response.setMessage("Builder not found.");
                return response;
            }
            Builder builder = opt.get();
            String logoFile = builder.getBuilderLogo();
            if (logoFile == null || logoFile.isBlank()) {
                response.setIsSuccess(0);
                response.setMessage("No logo to delete for this builder.");
                return response;
            }
            String slug = builder.getSlugUrl();
            if (slug != null && !slug.isBlank()) {
                String destDir = Paths.get(uploadDir, "builders", slug).toString();
                String sep = destDir.endsWith(java.io.File.separator) ? "" : java.io.File.separator;
                fileUtils.deleteFileFromDestination(logoFile.trim(), destDir + sep);
            }
            builder.setBuilderLogo(null);
            builder.setUpdatedAt(LocalDateTime.now());
            builderRepository.save(builder);
            response.setIsSuccess(1);
            response.setMessage("Developer logo deleted successfully.");
        } catch (Exception e) {
            log.error("deleteDeveloperLogo failed: {}", e.getMessage());
            response.setIsSuccess(0);
            response.setMessage(e.getMessage() != null ? e.getMessage() : "Could not delete logo.");
        }
        return response;
    }

    /**
     * Upload developer logo and/or replace gallery images from a ZIP of images.
     * Files are stored under {@code {upload_dir}/builders/{slugUrl}/}.
     */
    @Transactional
    public Response uploadDeveloperMedia(int builderId, MultipartFile logo, MultipartFile galleryZip) {
        Response response = new Response();
        if ((logo == null || logo.isEmpty()) && (galleryZip == null || galleryZip.isEmpty())) {
            response.setIsSuccess(0);
            response.setMessage("Provide a logo image and/or a gallery .zip file.");
            return response;
        }
        Optional<Builder> opt = builderRepository.findById(builderId);
        if (opt.isEmpty()) {
            response.setIsSuccess(0);
            response.setMessage("Builder not found.");
            return response;
        }
        Builder builder = opt.get();
        String slug = builder.getSlugUrl();
        if (slug == null || slug.isBlank()) {
            response.setIsSuccess(0);
            response.setMessage("Builder has no slug; save the builder first.");
            return response;
        }
        String destDir = Paths.get(uploadDir, "builders", slug).toString();
        try {
            if (logo != null && !logo.isEmpty()) {
                if (!fileUtils.isTypeImage(logo)) {
                    response.setIsSuccess(0);
                    response.setMessage("Logo must be an image file.");
                    return response;
                }
                String oldLogo = builder.getBuilderLogo();
                String saved = fileUtils.saveDesktopImageWithResize(
                        logo, destDir, BUILDER_LOGO_MAX_W, BUILDER_LOGO_MAX_H, 0.9f);
                if (saved == null || saved.isBlank()) {
                    response.setIsSuccess(0);
                    response.setMessage("Could not save logo.");
                    return response;
                }
                if (oldLogo != null && !oldLogo.isBlank() && !oldLogo.equals(saved)) {
                    fileUtils.deleteFileFromDestination(oldLogo, destDir + java.io.File.separator);
                }
                builder.setBuilderLogo(saved);
            }

            if (galleryZip != null && !galleryZip.isEmpty()) {
                String zipName = galleryZip.getOriginalFilename();
                if (zipName == null || !zipName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    response.setIsSuccess(0);
                    response.setMessage("Gallery upload must be a .zip file.");
                    return response;
                }
                Map<String, byte[]> extracted = new LinkedHashMap<>();
                extractZipToMap(galleryZip.getInputStream(), extracted);
                List<Map.Entry<String, byte[]>> imageEntries = new ArrayList<>();
                for (Map.Entry<String, byte[]> e : extracted.entrySet()) {
                    if (isImageFileName(e.getKey())) {
                        imageEntries.add(e);
                        if (imageEntries.size() >= MAX_GALLERY_IMAGES_FROM_ZIP) {
                            break;
                        }
                    }
                }
                if (imageEntries.isEmpty()) {
                    response.setIsSuccess(0);
                    response.setMessage("No image files found in the ZIP.");
                    return response;
                }
                deleteExistingGalleryFiles(builder, destDir);
                List<String> names = new ArrayList<>();
                for (Map.Entry<String, byte[]> e : imageEntries) {
                    String saved = fileUtils.saveImageFromBytes(
                            e.getValue(),
                            e.getKey(),
                            destDir,
                            BUILDER_GALLERY_MAX_W,
                            BUILDER_GALLERY_MAX_H);
                    if (saved != null && !saved.isBlank()) {
                        names.add(saved);
                    }
                }
                if (names.isEmpty()) {
                    response.setIsSuccess(0);
                    response.setMessage("Could not save any images from the ZIP.");
                    return response;
                }
                builder.setDeveloperGalleryImagesJson(objectMapper.writeValueAsString(names));
            }

            builder.setUpdatedAt(LocalDateTime.now());
            builderRepository.save(builder);
            response.setIsSuccess(1);
            response.setMessage("Developer media updated successfully.");
        } catch (Exception e) {
            log.error("uploadDeveloperMedia failed: {}", e.getMessage());
            response.setIsSuccess(0);
            response.setMessage(e.getMessage() != null ? e.getMessage() : "Upload failed.");
        }
        return response;
    }

    /**
     * Bulk logo upload from a single ZIP. File names are matched to builder slug/name.
     * Example: "saya-homes.jpg" or "saya-homes-logo.png" maps to builder slug "saya-homes".
     */
    @Transactional
    public Response uploadBuilderLogosZip(MultipartFile logosZip) {
        Response response = new Response();
        if (logosZip == null || logosZip.isEmpty()) {
            return new Response(0, "ZIP file is required.", 0);
        }
        String zipName = logosZip.getOriginalFilename();
        if (zipName == null || !zipName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return new Response(0, "Only .zip file is allowed.", 0);
        }
        try {
            Map<String, Builder> bySlug = new HashMap<>();
            for (Builder b : builderRepository.findAll()) {
                if (b.getSlugUrl() != null && !b.getSlugUrl().isBlank()) {
                    bySlug.put(b.getSlugUrl().trim().toLowerCase(Locale.ROOT), b);
                }
                if (b.getBuilderName() != null && !b.getBuilderName().isBlank()) {
                    bySlug.putIfAbsent(
                            slugFromBuilderName(b.getBuilderName()).toLowerCase(Locale.ROOT), b);
                }
            }
            if (bySlug.isEmpty()) {
                return new Response(0, "No builders found to map logos.", 0);
            }

            Map<String, byte[]> extracted = new LinkedHashMap<>();
            extractZipToMap(logosZip.getInputStream(), extracted);
            int updated = 0;
            int skipped = 0;
            List<String> unmatched = new ArrayList<>();
            Set<Integer> touchedBuilderIds = new HashSet<>();

            for (Map.Entry<String, byte[]> entry : extracted.entrySet()) {
                String fileName = entry.getKey();
                if (!isImageFileName(fileName)) {
                    skipped++;
                    continue;
                }
                Builder builder = bySlug.get(resolveBuilderKeyFromFileName(fileName));
                if (builder == null) {
                    unmatched.add(fileName);
                    continue;
                }
                String slug = builder.getSlugUrl();
                if (slug == null || slug.isBlank()) {
                    unmatched.add(fileName);
                    continue;
                }
                String destDir = Paths.get(uploadDir, "builders", slug).toString();
                String saved = fileUtils.saveImageFromBytes(
                        entry.getValue(), fileName, destDir, BUILDER_LOGO_MAX_W, BUILDER_LOGO_MAX_H);
                if (saved == null || saved.isBlank()) {
                    skipped++;
                    continue;
                }
                String oldLogo = builder.getBuilderLogo();
                if (oldLogo != null && !oldLogo.isBlank() && !oldLogo.equals(saved)) {
                    fileUtils.deleteFileFromDestination(oldLogo, destDir + java.io.File.separator);
                }
                builder.setBuilderLogo(saved);
                builder.setUpdatedAt(LocalDateTime.now());
                touchedBuilderIds.add(builder.getId());
                updated++;
            }

            for (Builder b : bySlug.values()) {
                if (touchedBuilderIds.contains(b.getId())) {
                    builderRepository.save(b);
                }
            }

            response.setIsSuccess(1);
            String msg = "Bulk logo upload done. Updated " + updated + " builder(s)";
            if (skipped > 0) msg += ", skipped " + skipped + " file(s)";
            if (!unmatched.isEmpty()) {
                msg += ", unmatched: " + String.join(", ", unmatched.stream().limit(8).toList());
                if (unmatched.size() > 8) msg += " ...";
            }
            msg += ".";
            response.setMessage(msg);
            return response;
        } catch (Exception e) {
            log.error("uploadBuilderLogosZip failed: {}", e.getMessage());
            return new Response(0, e.getMessage() != null ? e.getMessage() : "Bulk upload failed.", 0);
        }
    }

    private String resolveBuilderKeyFromFileName(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String normalized = fileUtils.generateSlug(base).toLowerCase(Locale.ROOT);
        if (normalized.endsWith("-logo")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        return normalized;
    }

    private List<String> parseGalleryJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private void deleteExistingGalleryFiles(Builder builder, String destDir) {
        List<String> old = parseGalleryJson(builder.getDeveloperGalleryImagesJson());
        String sep = destDir.endsWith(java.io.File.separator) ? "" : java.io.File.separator;
        for (String f : old) {
            if (f != null && !f.isBlank()) {
                fileUtils.deleteFileFromDestination(f.trim(), destDir + sep);
            }
        }
    }

    private void extractZipToMap(InputStream zipIs, Map<String, byte[]> out) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(zipIs)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.contains("..") || name.contains("\\")) {
                    continue;
                }
                if (name.contains("/")) {
                    name = name.substring(name.lastIndexOf('/') + 1);
                }
                if (name.isBlank() || name.startsWith(".") || name.startsWith("__MACOSX")) {
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                out.put(name, baos.toByteArray());
            }
        }
    }

    private static boolean isImageFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp");
    }
    

    public List<Builder> getAllBuildersList() {
        return builderRepository.findAll();
    }
}
