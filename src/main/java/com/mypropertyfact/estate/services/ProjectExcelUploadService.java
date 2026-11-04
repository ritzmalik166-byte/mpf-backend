package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.entities.*;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class ProjectExcelUploadService {

    @Value("${upload_dir}")
    private String uploadDir;

    @Autowired
    private FileUtils fileUtils;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private BuilderRepository builderRepository;
    @Autowired
    private CityRepository cityRepository;
    @Autowired
    private StateRepository stateRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private ProjectStatusRepository projectStatusRepository;
    @Autowired
    private ProjectTypeRepository projectTypeRepository;
    @Autowired
    private ProjectGalleryRepository projectGalleryRepository;
    @Autowired
    private ProjectAboutRepository projectAboutRepository;
    @Autowired
    private ProjectDesktopBannerRepository projectDesktopBannerRepository;
    @Autowired
    private ProjectMobileBannerRepository projectMobileBannerRepository;
    @Autowired
    private ProjectWalkthroughRepository projectWalkthroughRepository;
    @Autowired
    private ListingActivityService listingActivityService;
    @Autowired
    private FloorPlanRepository floorPlanRepository;
    @Autowired
    private AmenityRepository amenityRepository;
    @Autowired
    private ProjectFaqsRepository projectFaqsRepository;
    @Autowired
    private LocationBenefitRepository locationBenefitRepository;

    @Autowired
    @Lazy
    private ProjectExcelUploadService self;

    @Autowired
    @Qualifier("projectImportJobExecutor")
    private ThreadPoolTaskExecutor importJobExecutor;

    @Autowired
    @Qualifier("projectImageExecutor")
    private ThreadPoolTaskExecutor imageExecutor;

    /** Background imports by job id; finished jobs are dropped after an hour. */
    private final Map<String, ProjectImportProgress> importJobs = new ConcurrentHashMap<>();
    private static final long JOB_RETENTION_MS = 60L * 60 * 1000;

    private static final int GALLERY_W = 1600;
    private static final int GALLERY_H = 1200;
    private static final int DESKTOP_W = 2508;
    private static final int DESKTOP_H = 1200;
    private static final int MOBILE_W = 800;
    private static final int MOBILE_H = 800;
    private static final int PROJECT_LOGO_WIDTH = 792;
    private static final int PROJECT_LOGO_HEIGHT = 203;
    private static final int PROJECT_LOCATION_WIDTH = 1200;
    private static final int PROJECT_LOCATION_HEIGHT = 800;

    /** Synchronous import (kept for API compatibility): returns once everything is saved. */
    public Response uploadProjectsExcel(MultipartFile excelFile, MultipartFile imagesZip) {
        Response invalid = validateUpload(excelFile);
        if (invalid != null) {
            return invalid;
        }
        try {
            byte[] excelBytes = excelFile.getBytes();
            Map<String, byte[]> imageMap = readImagesZip(imagesZip);
            return runImport(excelBytes, imageMap, new ProjectImportProgress());
        } catch (Exception e) {
            log.error("Excel upload failed", e);
            return failure("Failed to process Excel: " + e.getMessage());
        }
    }

    /**
     * Starts the import in the background and returns its job at once, so the admin panel can
     * poll {@link #getImportJob} for progress instead of holding one request open for minutes.
     * The files are read here, while the request (and its temp files) is still alive, and the
     * caller's security context is carried over so listing activity is still attributed.
     */
    public ProjectImportProgress startProjectsImport(MultipartFile excelFile, MultipartFile imagesZip) {
        Response invalid = validateUpload(excelFile);
        if (invalid != null) {
            ProjectImportProgress rejected = new ProjectImportProgress();
            rejected.finish(invalid);
            return rejected;
        }
        byte[] excelBytes;
        Map<String, byte[]> imageMap;
        try {
            excelBytes = excelFile.getBytes();
            imageMap = readImagesZip(imagesZip);
        } catch (Exception e) {
            ProjectImportProgress failed = new ProjectImportProgress();
            failed.finish(failure("Could not read the uploaded files: " + e.getMessage()));
            return failed;
        }

        purgeFinishedJobs();
        ProjectImportProgress progress = new ProjectImportProgress();
        importJobs.put(progress.getJobId(), progress);
        SecurityContext securityContext = SecurityContextHolder.getContext();
        try {
            importJobExecutor.execute(new DelegatingSecurityContextRunnable(() -> {
                try {
                    progress.finish(runImport(excelBytes, imageMap, progress));
                } catch (Exception e) {
                    log.error("Background Excel upload failed", e);
                    progress.finish(failure("Failed to process Excel: " + e.getMessage()));
                }
            }, securityContext));
        } catch (TaskRejectedException e) {
            importJobs.remove(progress.getJobId());
            progress.finish(failure("Too many imports are running. Please try again in a few minutes."));
        }
        return progress;
    }

    public Optional<ProjectImportProgress> getImportJob(String jobId) {
        return Optional.ofNullable(importJobs.get(jobId));
    }

    private void purgeFinishedJobs() {
        long cutoff = System.currentTimeMillis() - JOB_RETENTION_MS;
        importJobs.values().removeIf(job -> job.isDone() && job.getFinishedAt() < cutoff);
    }

    private static Response validateUpload(MultipartFile excelFile) {
        if (excelFile == null || excelFile.isEmpty()) {
            return failure("Excel file is required");
        }
        String filename = excelFile.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
            return failure("Only Excel files (.xlsx or .xls) are allowed");
        }
        return null;
    }

    private static Response failure(String message) {
        Response response = new Response();
        response.setIsSuccess(0);
        response.setMessage(message);
        return response;
    }

    private Map<String, byte[]> readImagesZip(MultipartFile imagesZip) {
        Map<String, byte[]> imageMap = new HashMap<>();
        if (imagesZip != null && !imagesZip.isEmpty() && imagesZip.getOriginalFilename() != null
                && imagesZip.getOriginalFilename().toLowerCase().endsWith(".zip")) {
            try (InputStream zipIs = imagesZip.getInputStream()) {
                extractZipToMap(zipIs, imageMap);
            } catch (Exception e) {
                log.warn("Could not extract images zip: {}", e.getMessage());
            }
        }
        return imageMap;
    }

    private Response runImport(byte[] excelBytes, Map<String, byte[]> imageMap, ProjectImportProgress progress)
            throws Exception {
        List<String> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        boolean hadImages = !imageMap.isEmpty();

        try (InputStream is = new ByteArrayInputStream(excelBytes);
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
                return failure("Excel must have a header row and at least one data row");
            }

            Row headerRow = sheet.getRow(0);
            Map<String, Integer> colIndex = buildHeaderIndex(headerRow);
            progress.startRows(sheet.getLastRowNum(), imageMap.size());

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    progress.rowDone();
                    continue;
                }

                String projectName = getCell(colIndex, row, "PROJECT NAME");
                if (projectName == null || projectName.trim().isEmpty()) {
                    errors.add("Row " + (r + 1) + ": Project name is required");
                    progress.rowDone();
                    continue;
                }

                try {
                    RowProcessOutcome outcome = self.processProjectRowInNewTransaction(
                            row, colIndex, projectName.trim(), r + 1);
                    if (outcome.error != null) {
                        errors.add(outcome.error);
                    } else if (outcome.created) {
                        created++;
                    } else if (outcome.updated) {
                        updated++;
                    }
                } catch (Exception e) {
                    errors.add("Row " + (r + 1) + ": " + e.getMessage());
                    log.warn("Row {} error: {}", r + 1, e.getMessage(), e);
                }
                progress.rowDone();
            }
        }

        // Process images only from zip (filename format: ProjectName-123_ImageType_My-Property-Fact)
        int imagesProcessed = 0;
        int imagesSkipped = 0;
        if (hadImages) {
            progress.startImages();
            int[] imageStats = processZipImages(imageMap, progress);
            imagesProcessed = imageStats[0];
            imagesSkipped = imageStats[1];
            log.info("Processed {} images from zip ({} skipped)", imagesProcessed, imagesSkipped);
        }

        StringBuilder msg = new StringBuilder();
        msg.append("Upload completed. Created: ").append(created).append(", Updated: ").append(updated);
        if (hadImages) {
            msg.append(". Images saved: ").append(imagesProcessed);
            if (imagesSkipped > 0) {
                msg.append(", images skipped (no matching project): ").append(imagesSkipped);
            }
        }
        if (!errors.isEmpty()) {
            msg.append(". Errors: ").append(errors.size()).append(" - ")
                    .append(String.join("; ", errors.subList(0, Math.min(10, errors.size()))));
            if (errors.size() > 10)
                msg.append("...");
        }
        Response response = new Response();
        response.setIsSuccess(1);
        response.setMessage(msg.toString());
        return response;
    }

    /**
     * Each Excel row runs in its own transaction so a failure on one row does not mark the
     * whole bulk upload as rollback-only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public RowProcessOutcome processProjectRowInNewTransaction(
            Row row, Map<String, Integer> colIndex, String projectName, int rowNum) {
        List<String> rowErrors = new ArrayList<>();
        Project project = resolveOrCreateProject(row, colIndex, projectName, rowErrors, rowNum);
        if (project == null) {
            String message = rowErrors.isEmpty()
                    ? "Row " + rowNum + ": Could not resolve project"
                    : rowErrors.get(0);
            return RowProcessOutcome.error(message);
        }

        String slug = project.getSlugURL();
        if (slug == null || slug.isEmpty()) {
            slug = fileUtils.generateSlug(projectName);
            project.setSlugURL(slug);
        }

        boolean isNew = project.getId() == 0;
        if (isNew && project.getCreatedBy() == null) {
            User creator = ListingActivityService.currentUserOrNull();
            if (creator != null) {
                project.setCreatedBy(creator);
            }
        }
        projectRepository.save(project);
        listingActivityService.record(
                ListingActivityService.SOURCE_PROJECT,
                (long) project.getId(),
                isNew ? ListingActivityService.ACTION_CREATED : ListingActivityService.ACTION_UPDATED,
                isNew ? project.getProjectName() : "Updated via Excel upload");

        Optional<ProjectWalkthrough> existingWalkthrough =
                projectWalkthroughRepository.findFirstByProject_IdOrderByIdDesc(project.getId());
        if (existingWalkthrough.isEmpty()) {
            String walkthroughDesc = getCell(colIndex, row, "PROJECT WALKTHROUGH DESCRIPTION");
            if (walkthroughDesc != null && !walkthroughDesc.trim().isEmpty()) {
                ProjectWalkthrough projectWalkthrough = new ProjectWalkthrough();
                projectWalkthrough.setWalkthroughDesc(walkthroughDesc.trim());
                projectWalkthrough.setProject(project);
                projectWalkthroughRepository.save(projectWalkthrough);
            }
        }

        String aboutDesc = getCell(colIndex, row, "PROJECT ABOUT SHORT DESCRIPTION");
        if (aboutDesc != null && !aboutDesc.trim().isEmpty()) {
            ProjectsAbout about = projectAboutRepository
                    .findFirstByProject_IdOrderByIdDesc(project.getId()).orElse(null);
            if (about == null) {
                about = new ProjectsAbout();
                about.setProject(project);
            }
            about.setLongDesc(aboutDesc.trim());
            projectAboutRepository.save(about);
        }

        String projectConfiguration = getCell(colIndex, row, "PROJECT CONFIGURATION");
        if (projectConfiguration != null && !projectConfiguration.trim().isEmpty()) {
            saveFloorPlansFromConfiguration(project, projectConfiguration.trim());
        }

        String projectAmenities = getCellWithFallback(colIndex, row, "PROJECT AMENITIES", "PROJECT Amenities");
        if (projectAmenities != null && !projectAmenities.trim().isEmpty()) {
            linkAmenitiesFromColumn(project, projectAmenities.trim());
        }

        String locationAdvantage = getCell(colIndex, row, "LOCATION ADVANTAGE");
        if (locationAdvantage != null && !locationAdvantage.trim().isEmpty()) {
            saveLocationAdvantagesFromColumn(project, locationAdvantage.trim());
        }

        saveFaqsFromRow(project, row, colIndex);

        return isNew ? RowProcessOutcome.created() : RowProcessOutcome.updated();
    }

    private static final class RowProcessOutcome {
        private final String error;
        private final boolean created;
        private final boolean updated;

        private RowProcessOutcome(String error, boolean created, boolean updated) {
            this.error = error;
            this.created = created;
            this.updated = updated;
        }

        static RowProcessOutcome error(String error) {
            return new RowProcessOutcome(error, false, false);
        }

        static RowProcessOutcome created() {
            return new RowProcessOutcome(null, true, false);
        }

        static RowProcessOutcome updated() {
            return new RowProcessOutcome(null, false, true);
        }
    }

    /** One zip image the import will write, decided before any resizing starts. */
    record PlannedImage(String filename, int projectId, String kind, String baseName,
                                String altTag, String directory, int width, int height) {
    }

    /** A resized image now on disk, waiting for its database row. */
    record SavedImage(PlannedImage plan, String storedName) {
    }

    /** What a project already has, loaded once per project instead of once per image. */
    private static final class ProjectImageState {
        final Project project;
        final String slug;
        Set<String> galleryAltTags;
        Set<String> desktopAltTags;
        Set<String> mobileAltTags;
        boolean logoTaken;
        boolean locationTaken;
        boolean thumbnailTaken;

        ProjectImageState(Project project, String slug) {
            this.project = project;
            this.slug = slug;
            this.logoTaken = project.getProjectLogo() != null && !project.getProjectLogo().isBlank();
            this.locationTaken = project.getLocationMap() != null && !project.getLocationMap().isBlank();
            this.thumbnailTaken = project.getProjectThumbnail() != null && !project.getProjectThumbnail().isBlank();
        }
    }

    /**
     * Process images from the zip map. Filename format: {@code ProjectName-123_ImageType_My-Property-Fact}
     * First segment = project identifier (with hyphens), second = image type (e.g. Gallery-2, Opening-1), third = ignored.
     * Images are saved under the project folder with unique names and generated alt tags.
     *
     * <p>Runs in three steps so ~400 images take seconds per project rather than minutes:
     * plan every file against each project's existing images (one lookup per project),
     * resize the planned files in parallel, then insert each project's rows in one transaction.
     * The rules for which files are saved or skipped are the same as before.
     *
     * @return int[]{savedCount, skippedCount}
     */
    private int[] processZipImages(Map<String, byte[]> imageMap, ProjectImportProgress progress) {
        Map<String, Optional<Project>> projectsByKey = new HashMap<>();
        Map<Integer, ProjectImageState> states = new HashMap<>();
        List<PlannedImage> plans = new ArrayList<>();
        int skipped = 0;

        List<String> filenames = new ArrayList<>(imageMap.keySet());
        Collections.sort(filenames);
        for (String filename : filenames) {
            byte[] data = imageMap.get(filename);
            if (data == null || data.length == 0) {
                progress.imageDone();
                continue;
            }
            String baseName = filename;
            if (baseName.contains("/"))
                baseName = baseName.substring(baseName.lastIndexOf('/') + 1);
            int dot = baseName.lastIndexOf('.');
            if (dot > 0)
                baseName = baseName.substring(0, dot);

            String[] parts = baseName.split("_");
            if (parts.length < 2) {
                log.debug("Skipping zip file (expected format ProjectName_ImageType_*): {}", filename);
                progress.imageDone();
                continue;
            }
            String projectKey = parts[0].trim();
            String imageType = parts[1].trim();
            if (projectKey.isEmpty() || imageType.isEmpty()) {
                progress.imageDone();
                continue;
            }

            Optional<Project> projectOpt = projectsByKey.computeIfAbsent(projectKey, this::resolveProjectFromZipKey);
            if (projectOpt.isEmpty()) {
                log.debug("No project found for zip key '{}' (file '{}'), skipping image", projectKey, filename);
                skipped++;
                progress.imageDone();
                continue;
            }

            Project project = projectOpt.get();
            ProjectImageState state = states.computeIfAbsent(project.getId(), id -> {
                String slug = project.getSlugURL();
                if (slug == null || slug.isEmpty())
                    slug = fileUtils.generateSlug(project.getProjectName());
                return new ProjectImageState(project, slug);
            });
            PlannedImage plan = planImage(filename, imageType, state);
            if (plan == null) {
                progress.imageDone();
            } else {
                plans.add(plan);
            }
        }

        List<Future<SavedImage>> futures = new ArrayList<>(plans.size());
        for (PlannedImage plan : plans) {
            // Hand the bytes to the worker and drop the map's copy so memory frees as images finish.
            byte[] data = imageMap.remove(plan.filename());
            futures.add(imageExecutor.submit(() -> {
                try {
                    String stored = fileUtils.saveImageFromBytes(
                            data, plan.baseName(), plan.directory(), plan.width(), plan.height());
                    return stored != null ? new SavedImage(plan, stored) : null;
                } finally {
                    progress.imageDone();
                }
            }));
        }
        imageMap.clear();

        Map<Integer, List<SavedImage>> savedByProject = new LinkedHashMap<>();
        for (Future<SavedImage> future : futures) {
            try {
                SavedImage saved = future.get();
                if (saved != null) {
                    savedByProject.computeIfAbsent(saved.plan().projectId(), id -> new ArrayList<>()).add(saved);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn("Could not save zip image: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }

        int count = 0;
        for (Map.Entry<Integer, List<SavedImage>> entry : savedByProject.entrySet()) {
            try {
                count += self.saveProjectImagesInNewTransaction(entry.getKey(), states.get(entry.getKey()).slug, entry.getValue());
            } catch (Exception e) {
                log.warn("Could not record {} zip images for project {}: {}",
                        entry.getValue().size(), entry.getKey(), e.getMessage());
            }
        }
        return new int[]{count, skipped};
    }

    /**
     * Decides where a zip image goes, or returns null when it would duplicate an image the
     * project already has (or one planned earlier in this upload).
     */
    private PlannedImage planImage(String filename, String imageType, ProjectImageState state) {
        Project project = state.project;
        String projectDir = uploadDir + "properties/" + state.slug + "/";
        String uniqueBaseName = sanitizeImageType(imageType) + "_" + UUID.randomUUID().toString().substring(0, 8);
        String altTag = generateAltTag(project.getProjectName(), imageType);
        String type = imageType.toLowerCase();

        if (type.startsWith("desktop")) {
            if (state.desktopAltTags == null) {
                state.desktopAltTags = new HashSet<>();
                projectDesktopBannerRepository.findByProject(project)
                        .forEach(b -> state.desktopAltTags.add(b.getDesktopAltTag()));
            }
            if (!state.desktopAltTags.add(altTag)) return null;
            return new PlannedImage(filename, project.getId(), "desktop", uniqueBaseName, altTag, projectDir, DESKTOP_W, DESKTOP_H);
        }
        if (type.startsWith("mobile")) {
            if (state.mobileAltTags == null) {
                state.mobileAltTags = new HashSet<>();
                projectMobileBannerRepository.findByProject(project)
                        .forEach(b -> state.mobileAltTags.add(b.getMobileAltTag()));
            }
            if (!state.mobileAltTags.add(altTag)) return null;
            return new PlannedImage(filename, project.getId(), "mobile", uniqueBaseName, altTag, projectDir, MOBILE_W, MOBILE_H);
        }
        if (type.startsWith("logo")) {
            if (state.logoTaken) return null;
            state.logoTaken = true;
            return new PlannedImage(filename, project.getId(), "logo", uniqueBaseName, altTag, projectDir, PROJECT_LOGO_WIDTH, PROJECT_LOGO_HEIGHT);
        }
        if (type.startsWith("location")) {
            if (state.locationTaken) return null;
            state.locationTaken = true;
            return new PlannedImage(filename, project.getId(), "location", uniqueBaseName, altTag, projectDir, PROJECT_LOCATION_WIDTH, PROJECT_LOCATION_HEIGHT);
        }
        if (type.startsWith("thumbnail")) {
            if (state.thumbnailTaken) return null;
            state.thumbnailTaken = true;
            return new PlannedImage(filename, project.getId(), "thumbnail", uniqueBaseName, altTag, projectDir, MOBILE_W, MOBILE_H);
        }
        // "gallery-*" and any other type are saved as gallery images.
        if (state.galleryAltTags == null) {
            state.galleryAltTags = new HashSet<>();
            projectGalleryRepository.findBySlugUrl(state.slug)
                    .forEach(g -> state.galleryAltTags.add(g.getAltTag()));
        }
        if (!state.galleryAltTags.add(altTag)) return null;
        return new PlannedImage(filename, project.getId(), "gallery", uniqueBaseName, altTag, projectDir, GALLERY_W, GALLERY_H);
    }

    /** Inserts one project's resized images in a single transaction. @return rows saved */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int saveProjectImagesInNewTransaction(int projectId, String slug, List<SavedImage> images) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return 0;
        }
        List<ProjectGallery> galleries = new ArrayList<>();
        List<ProjectDesktopBanner> desktopBanners = new ArrayList<>();
        List<ProjectMobileBanner> mobileBanners = new ArrayList<>();
        boolean projectChanged = false;
        int count = 0;

        for (SavedImage image : images) {
            PlannedImage plan = image.plan();
            switch (plan.kind()) {
                case "desktop" -> {
                    ProjectDesktopBanner banner = new ProjectDesktopBanner();
                    banner.setProject(project);
                    banner.setDesktopImage(image.storedName());
                    banner.setDesktopAltTag(plan.altTag());
                    desktopBanners.add(banner);
                }
                case "mobile" -> {
                    ProjectMobileBanner banner = new ProjectMobileBanner();
                    banner.setProject(project);
                    banner.setMobileImage(image.storedName());
                    banner.setMobileAltTag(plan.altTag());
                    mobileBanners.add(banner);
                }
                case "logo" -> {
                    project.setProjectLogo(image.storedName());
                    projectChanged = true;
                }
                case "location" -> {
                    project.setLocationMap(image.storedName());
                    projectChanged = true;
                }
                case "thumbnail" -> {
                    project.setProjectThumbnail(image.storedName());
                    projectChanged = true;
                }
                default -> {
                    ProjectGallery gallery = new ProjectGallery();
                    gallery.setProject(project);
                    gallery.setImage(image.storedName());
                    gallery.setSlugUrl(slug);
                    gallery.setType("gallery");
                    gallery.setAltTag(plan.altTag());
                    galleries.add(gallery);
                }
            }
            count++;
        }

        projectGalleryRepository.saveAll(galleries);
        projectDesktopBannerRepository.saveAll(desktopBanners);
        projectMobileBannerRepository.saveAll(mobileBanners);
        if (projectChanged) {
            projectRepository.save(project);
        }
        return count;
    }

    private Optional<Project> resolveProjectFromZipKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            return Optional.empty();
        }
        String nameGuess = projectKey.replace("-", " ").trim();
        String slugFromZip = fileUtils.generateSlug(nameGuess);
        Optional<Project> projectOpt = projectRepository.findBySlugURL(slugFromZip);
        if (projectOpt.isPresent()) {
            return projectOpt;
        }
        return projectRepository.findFirstByProjectNameIgnoreCase(nameGuess);
    }

    private static String sanitizeImageType(String imageType) {
        return imageType.replaceAll("[^a-zA-Z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "").toLowerCase();
    }

    private static String generateAltTag(String projectName, String imageType) {
        String humanType = imageType.replace("-", " ").replace("_", " ");
        if (projectName == null || projectName.trim().isEmpty())
            return humanType;
        return projectName.trim() + " - " + humanType;
    }

    private Map<String, Integer> buildHeaderIndex(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        if (headerRow == null)
            return map;
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell c = headerRow.getCell(i);
            String val = getCellValueAsString(c);
            if (val != null && !val.trim().isEmpty()) {
                map.put(val.trim().toUpperCase().replaceAll("\\s+", " "), i);
            }
        }
        return map;
    }

    private String getCell(Map<String, Integer> colIndex, Row row, String headerName) {
        Integer idx = colIndex.get(headerName.toUpperCase().replaceAll("\\s+", " "));
        if (idx == null)
            return null;
        Cell c = row.getCell(idx.intValue());
        return getCellValueAsString(c);
    }

    private String getCellWithFallback(Map<String, Integer> colIndex, Row row, String primaryHeader, String fallbackHeader) {
        String val = getCell(colIndex, row, primaryHeader);
        if (val != null && !val.trim().isEmpty())
            return val;
        return getCell(colIndex, row, fallbackHeader);
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null)
            return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double n = cell.getNumericCellValue();
                if (n == Math.floor(n))
                    return String.valueOf((long) n);
                return String.valueOf(n);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return cell.getStringCellValue();
                }
            default:
                return null;
        }
    }

    private Project resolveOrCreateProject(Row row, Map<String, Integer> colIndex, String projectName,
                                          List<String> errors, int rowNum) {
        String builderName = getCell(colIndex, row,"BUILDER NAME");
        String cityName = getCell(colIndex, row, "CITY NAME");
        String stateName = getCell(colIndex, row, "STATE NAME");
        String countryName = getCell(colIndex, row, "COUNTRY NAME");
        String projectStatusName = getCell(colIndex, row, "PROJECT STATUS NAME");
        String propertyTypeName = getCell(colIndex, row, "PROPERTY TYPE NAME");

        if (builderName == null || builderName.trim().isEmpty()) {
            errors.add("Row " + rowNum + ": Builder / Project organization is required");
            return null;
        }
        if (cityName == null || cityName.trim().isEmpty()) {
            errors.add("Row " + rowNum + ": City name is required");
            return null;
        }

        Builder builder = findOrCreateBuilder(builderName.trim());
        Country country = findOrCreateCountry(countryName != null ? countryName.trim() : "India");
        State state = findOrCreateState(stateName != null ? stateName.trim() : "N/A", country);
        City city = findOrCreateCity(cityName.trim(), state);
        String statusName = (projectStatusName != null && !projectStatusName.trim().isEmpty())
                ? projectStatusName.trim()
                : "Under Construction";
        ProjectStatus status = projectStatusRepository.findByStatusNameIgnoreCase(statusName)
                .orElseGet(() -> projectStatusRepository.findAll().stream().findFirst().orElse(null));
        ProjectTypes projectType = null;
        if (propertyTypeName != null && !propertyTypeName.trim().isEmpty()) {
            projectType = projectTypeRepository.findByProjectTypeNameIgnoreCase(propertyTypeName.trim())
                    .orElse(null);
        }
        if (projectType == null) {
            projectType = projectTypeRepository.findAll().stream().findFirst().orElse(null);
        }

        Optional<Project> existing = projectRepository.findBySlugURL(fileUtils.generateSlug(projectName));
        if (existing.isPresent()) {
            Project p = existing.get();
            mapRowToProject(p, row, colIndex, builder, city, status, projectType);
            return p;
        }

        Project project = new Project();
        project.setProjectName(projectName);
        project.setSlugURL(fileUtils.generateSlug(projectName));
        project.setStatus(true);
        project.setShowFeaturedProperties(false);
        mapRowToProject(project, row, colIndex, builder, city, status, projectType);
        return project;
    }

    private void mapRowToProject(Project project, Row row, Map<String, Integer> colIndex,
                                 Builder builder, City city, ProjectStatus status, ProjectTypes projectType) {
        project.setBuilder(builder);
        project.setCity(city);
        if (status != null)
            project.setProjectStatus(status);
        if (projectType != null)
            project.setProjectTypes(projectType);
        
        setIfPresent(project::setProjectLocality, getCell(colIndex, row, "PROJECT LOCALITY"));
        setIfPresent(project::setProjectConfiguration, getCell(colIndex, row, "PROJECT CONFIGURATION"));
        setIfPresent(project::setProjectPrice, getCell(colIndex, row, "PROJECT PRICE"));
        setIfPresent(project::setReraNo, getCell(colIndex, row, "RERA NO"));
        setIfPresent(project::setFloorPlanDesc, getCell(colIndex, row, "FLOOR PLAN DESCRIPTION"));
        setIfPresent(project::setLocationDesc, getCell(colIndex, row, "LOCATION DESCRIPTION"));
        setIfPresent(project::setAmenityDesc, getCell(colIndex, row, "AMENITY DESCRIPTION"));
        setIfPresent(project::setMetaTitle, getCell(colIndex, row, "META TITLE"));
        setIfPresent(project::setMetaDescription, getCell(colIndex, row, "META DESCRIPTION"));
        setIfPresent(project::setMetaKeyword, getCell(colIndex, row, "META KEYWORD"));
        setIfPresent(project::setIvrNo, getCell(colIndex, row, "IVR NO"));
        setIfPresent(project::setReraWebsite, getCell(colIndex, row, "RERA WEBSITE"));
    }

    private static void setIfPresent(java.util.function.Consumer<String> setter, String value) {
        if (value != null && !value.trim().isEmpty())
            setter.accept(value.trim());
    }

    /**
     * Parse PROJECT CONFIGURATION string (e.g. "3 BHK-1333 sq.ft, 4 BHK-1744 sq.ft") and save each
     * plan type + area into the floor_plans table. Replaces any existing floor plans for this project.
     */
    private void saveFloorPlansFromConfiguration(Project project, String projectConfiguration) {
        if (projectConfiguration == null || projectConfiguration.trim().isEmpty())
            return;
        floorPlanRepository.deleteAll(floorPlanRepository.findByProject(project));
        Pattern areaPattern = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:sq\\.?ft|sq\\.?\\s*ft|sqft)?", Pattern.CASE_INSENSITIVE);
        for (String part : projectConfiguration.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty())
                continue;
            int dashIdx = trimmed.indexOf('-');
            if (dashIdx <= 0 || dashIdx == trimmed.length() - 1)
                continue;
            String planType = trimmed.substring(0, dashIdx).trim();
            String areaStr = trimmed.substring(dashIdx + 1).trim();
            if (planType.isEmpty())
                continue;
            Matcher m = areaPattern.matcher(areaStr);
            Double areaSqft = null;
            if (m.find()) {
                try {
                    areaSqft = Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {
                    // keep null
                }
            }
            FloorPlan fp = new FloorPlan();
            fp.setProject(project);
            fp.setPlanType(planType);
            fp.setAreaSqft(areaSqft);
            if (areaSqft != null)
                fp.setAreaSqmt(Math.round(areaSqft * 0.092903 * 100.0) / 100.0);
            floorPlanRepository.save(fp);
        }
    }

    /**
     * Parse PROJECT AMENITIES column (e.g. "Swimming-Pool_Gymnasium_Club-House_Waiting-Lounge"),
     * split by underscore, find each in the amenities table and add to the project. Only existing
     * amenities are linked; names not found are skipped.
     */
    private void linkAmenitiesFromColumn(Project project, String projectAmenities) {
        if (projectAmenities == null || projectAmenities.trim().isEmpty())
            return;
        Set<Amenity> amenities = project.getAmenities();
        if (amenities == null) {
            amenities = new HashSet<>();
            project.setAmenities(amenities);
        }
        for (String part : splitListColumn(projectAmenities)) {
            String name = part.trim().replace("-", " ");
            if (name.isEmpty())
                continue;
            Optional<Amenity> opt = amenityRepository.findByTitleIgnoreCase(name);
            if (opt.isEmpty()) {
                opt = amenityRepository.findByTitleIgnoreCase(name.replace("-", " "));
            }
            opt.ifPresent(amenities::add);
        }
        projectRepository.save(project);
    }

    /**
     * Parse LOCATION ADVANTAGE column (e.g. "Hyatt-Regency-Gurugram-4.2-Km_Entertainland-Mall-4.4-Km_").
     * Split by underscore; each segment is "Name-Distance-Km". Replaces existing location benefits for the project.
     */
    private void saveLocationAdvantagesFromColumn(Project project, String locationAdvantage) {
        if (locationAdvantage == null || locationAdvantage.trim().isEmpty())
            return;
        locationBenefitRepository.deleteAll(locationBenefitRepository.findByProject(project));
        Pattern legacyPattern = Pattern.compile("^(.+)-([0-9]+(?:\\.[0-9]+)?)-Km$", Pattern.CASE_INSENSITIVE);
        Pattern pipePattern = Pattern.compile("^(.+?)\\s*\\(([0-9]+(?:\\.[0-9]+)?)\\s*Km\\)\\s*$",
                Pattern.CASE_INSENSITIVE);
        for (String part : splitListColumn(locationAdvantage)) {
            String segment = part.trim();
            if (segment.isEmpty())
                continue;

            String benefitName = null;
            String distance = null;
            Matcher legacyMatch = legacyPattern.matcher(segment);
            if (legacyMatch.matches()) {
                benefitName = legacyMatch.group(1).trim().replace("-", " ");
                distance = legacyMatch.group(2) + " Km";
            } else {
                Matcher pipeMatch = pipePattern.matcher(segment);
                if (pipeMatch.matches()) {
                    benefitName = pipeMatch.group(1).trim();
                    distance = pipeMatch.group(2) + " Km";
                }
            }
            if (benefitName == null || benefitName.isEmpty())
                continue;

            LocationBenefit lb = new LocationBenefit();
            lb.setProject(project);
            lb.setBenefitName(benefitName);
            lb.setDistance(distance != null ? distance : "");
            locationBenefitRepository.save(lb);
        }
    }

    /** Supports underscore, pipe, and comma separated Excel values. */
    private static String[] splitListColumn(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        return value.split("\\s*\\|\\s*|_|,");
    }

    /**
     * Read Question 1, Answer 1, Question 2, Answer 2, ... from row and save as project FAQs. Replaces existing FAQs for the project.
     */
    private void saveFaqsFromRow(Project project, Row row, Map<String, Integer> colIndex) {
        projectFaqsRepository.deleteAll(projectFaqsRepository.findByProject(project));
        String slug = project.getSlugURL() != null ? project.getSlugURL() : fileUtils.generateSlug(project.getProjectName());
        for (int i = 1; i <= 20; i++) {
            String question = getCell(colIndex, row, "Question " + i);
            String answer = getCell(colIndex, row, "Answer " + i);
            if (question == null && answer == null)
                continue;
            if (question != null) question = question.trim();
            if (answer != null) answer = answer.trim();
            if ((question == null || question.isEmpty()) && (answer == null || answer.isEmpty()))
                continue;
            ProjectFaqs faq = new ProjectFaqs();
            faq.setProject(project);
            faq.setFaqQuestion(question != null ? question : "");
            faq.setFaqAnswer(answer != null ? answer : "");
            faq.setSlugUrl(slug);
            projectFaqsRepository.save(faq);
        }
    }

    private Builder findOrCreateBuilder(String name) {
        return builderRepository.findByBuilderNameIgnoreCase(name)
                .orElseGet(() -> {
                    Builder b = new Builder();
                    b.setBuilderName(name);
                    b.setSlugUrl(fileUtils.generateSlug(name));
                    return builderRepository.save(b);
                });
    }

    private Country findOrCreateCountry(String name) {
        return countryRepository.findByCountryNameIgnoreCase(name)
                .orElseGet(() -> {
                    Country c = new Country();
                    c.setCountryName(name);
                    return countryRepository.save(c);
                });
    }

    private State findOrCreateState(String name, Country country) {
        Optional<State> opt = stateRepository.findByStateName(name);
        if (opt.isPresent())
            return opt.get();
        State s = new State();
        s.setStateName(name);
        s.setCountry(country);
        return stateRepository.save(s);
    }

    private City findOrCreateCity(String name, State state) {
        Optional<City> opt = cityRepository.findByNameIgnoreCase(name);
        if (opt.isPresent())
            return opt.get();
        City c = new City();
        c.setName(name);
        c.setSlugUrl(fileUtils.generateSlug(name));
        c.setState(state);
        return cityRepository.save(c);
    }

    private void extractZipToMap(InputStream zipIs, Map<String, byte[]> out) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(zipIs)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory())
                    continue;
                String name = entry.getName();
                if (name.contains("/"))
                    name = name.substring(name.lastIndexOf('/') + 1);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0)
                    baos.write(buf, 0, n);
                out.put(name, baos.toByteArray());
            }
        }
    }
}
