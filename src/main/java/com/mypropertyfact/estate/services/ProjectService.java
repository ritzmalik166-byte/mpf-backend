package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.Constants;
import com.mypropertyfact.estate.common.CommonMapper;
import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.dtos.*;
import com.mypropertyfact.estate.entities.*;
import com.mypropertyfact.estate.models.ProjectAmenityDto;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectRepository projectRepository;
    private final ProjectCatalogService projectCatalogService;
    private final AmenityRepository amenityRepository;
    private final CityRepository cityRepository;
    private final StateRepository stateRepository;
    private final BuilderRepository builderRepository;
    private final ProjectTypeRepository projectTypeRepository;
    private final FileUtils fileUtils;
    private final ProjectStatusRepository projectStatusRepository;
    private final CommonMapper commonMapper;
    private final ProjectDesktopBannerRepository desktopBannerRepository;
    private final ProjectMobileBannerRepository mobileBannerRepository;
    private final FloorPlanRepository floorPlanRepository;
    private final ProjectGalleryRepository projectGalleryRepository;
    private final LocationBenefitRepository locationBenefitRepository;
    private final ProjectFaqsRepository faqsRepository;
    private final ListingActivityService listingActivityService;

    @Value("${upload_dir}")
    private String uploadDir; // D:/my-property-fact/public/

    @Transactional
    public List<ProjectDetailDto> fetchAllProjects() {
        List<Project> projects = projectRepository.findAll(Sort.by(Sort.Direction.ASC, "projectName"));
        return projects.stream().map(project -> {
            ProjectDetailDto detailDto = new ProjectDetailDto();
            commonMapper.mapFullProjectDetailToDetailedDto(project, detailDto);
            return detailDto;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectShortDetails> searchByPropertyTypeLocationBudget(
            String propertyType, String propertyLocation, String budget) {

        float[] range = getBudgetRange(budget);
        Integer type = propertyType.isEmpty() ? null : Integer.parseInt(propertyType);
        Integer city = propertyLocation.isEmpty() ? null : Integer.parseInt(propertyLocation);

        List<Project> projects = projectRepository.searchProjects(type, city, range[0], range[1]);

        return projects.stream().map(p -> {
            ProjectShortDetails dto = new ProjectShortDetails();
            commonMapper.mapShortProjectDetails(p, dto);
            return dto;
        }).toList();
    }

    private float[] getBudgetRange(String budget) {
        if (budget == null || budget.isBlank()) {
            return new float[] { 0, 20 };
        }
        String normalized = budget.trim()
                .replace("₹", "")
                .replace("–", "-")
                .replace("—", "-")
                .replaceAll("\\s+", " ")
                .toLowerCase();

        // Accept website, chat, and API budget labels used by the chatbot / filters.
        if (normalized.contains("up to") && normalized.contains("1")) {
            return new float[] { 0, 1 };
        }
        if ((normalized.contains("1-3") || normalized.contains("1cr-3cr") || normalized.contains("1 cr - 3"))
                && !normalized.contains("up to")) {
            return new float[] { 1, 3 };
        }
        if (normalized.contains("3-5") || normalized.contains("3cr-5cr") || normalized.contains("3 cr - 5")) {
            return new float[] { 3, 5 };
        }
        if (normalized.contains("above") && normalized.contains("5")) {
            return new float[] { 5, 20 };
        }

        return switch (budget.trim()) {
            case "Up to 1Cr*", "Up to 1Cr", "Up to ₹1 Cr" -> new float[] { 0, 1 };
            case "1-3 Cr*", "1Cr-3Cr", "₹1 Cr – ₹3 Cr", "₹1 Cr - ₹3 Cr" -> new float[] { 1, 3 };
            case "3-5 Cr*", "3Cr-5Cr", "₹3 Cr – ₹5 Cr", "₹3 Cr - ₹5 Cr" -> new float[] { 3, 5 };
            case "Above 5 Cr*", "Above 5Cr", "Above ₹5 Cr" -> new float[] { 5, 20 };
            default -> new float[] { 0, 20 };
        };
    }

    @Transactional
    public Response addUpdateAmenity(ProjectAmenityDto dto) {
        Optional<Project> dbProject = projectRepository.findById(dto.getProjectId());

        if (dbProject.isEmpty()) {
            return new Response(0, "Project not found", 0);
        }

        Project project = dbProject.get();
        Set<Amenity> dtoAmenities = new HashSet<>();
        if (dto.getAmenityList() != null) {
            Set<Amenity> list = dto.getAmenityList().stream()
                    .map(amenity -> amenityRepository.findById(amenity.getId()).orElse(null))
                    .collect(Collectors.toSet());
            dtoAmenities.addAll(list);
        }
        if (dtoAmenities.isEmpty()) {
            return new Response(0, "No amenities provided", 0);
        }
        project.setAmenities(dtoAmenities);
        // Save the project and its associations
        projectRepository.save(project);

        return new Response(1, "Amenities saved successfully", 0);
    }

    @Transactional
    public ProjectDetailDto getBySlugUrl(String url) {
        log.info("Fetching project by slug: {}", url);
        Optional<Project> projectData = projectRepository.findBySlugURLWithAllRelations(url);

        // If not found by the main query, try finding by slug directly to check if
        // project exists
        if (projectData.isEmpty()) {
            log.warn("Project not found with approved/published status, checking if project exists with slug: {}", url);
            // Try without status filter first to see if project exists
            Optional<Project> projectBySlugNoFilter = projectRepository.findBySlugURLWithAllRelationsNoFilter(url);
            if (projectBySlugNoFilter.isPresent()) {
                Project project = projectBySlugNoFilter.get();
                log.info("Project found with slug: {} - ID: {}, Name: {}", url, project.getId(), project.getProjectName());
            } else {
                // Try with simple findBySlugURL to verify slug exists at all
                Optional<Project> projectBySlug = projectRepository.findBySlugURL(url);
                if (projectBySlug.isPresent()) {
                    Project project = projectBySlug.get();
                    log.error("Project found with simple query but not with EntityGraph - ID: {}, Name: {}",
                            project.getId(), project.getProjectName());
                } else {
                    log.error("No project found with slug: {} - Slug does not exist in database", url);
                }
            }
        }

        ProjectDetailDto detailDto = new ProjectDetailDto();
        if (projectData.isPresent()) {
            Project project = projectData.get();
            commonMapper.mapFullProjectDetailToDetailedDto(project, detailDto);
        } else {
            log.error("Returning empty DTO - no project found matching criteria");
        }
        return detailDto;
    }

    @Transactional
    public Response deleteProject(int id) {
        Response response = new Response();
        try {
            if (id > 0) {
                Optional<Project> optionalProject = projectRepository.findById(id);
                if (optionalProject.isPresent()) {
                    Project project = optionalProject.get();

                    // 1. Clear project-amenities relationship
                    project.getAmenities().clear();
                    projectRepository.save(project); // Persist the relationship removal

                    // 2. Delete the directory (if applicable)
                    String dirPath = uploadDir.concat("properties/") + project.getSlugURL();
                    deleteDirectory(dirPath); // Make sure this handles exceptions safely

                    // 3. Delete the project
                    projectRepository.delete(project);

                    // 4. Set success response
                    response.setMessage("Project deleted successfully...");
                    response.setIsSuccess(1);
                } else {
                    response.setMessage("No project found with the given ID!");
                    response.setIsSuccess(0);
                }
            } else {
                response.setMessage("Invalid project ID!");
                response.setIsSuccess(0);
            }
        } catch (Exception e) {
            response.setMessage("Error deleting project: " + e.getMessage());
            response.setIsSuccess(0);
            e.printStackTrace();
        }
        return response;
    }

    private void deleteDirectory(String dirPath) {
        File directory = new File(dirPath);
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete(); // Delete each file in the directory
                }
            }
            directory.delete(); // Delete the empty directory
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "projectShortDetails", allEntries = true)
    public Response saveProject(MultipartFile projectLogo,
            MultipartFile locationMap,
            MultipartFile projectThumbnail,
            AddUpdateProjectDto addUpdateProjectDto) {
        Response response = new Response();
        List<String> savedFiles = new ArrayList<>();
        String fileDestination = null;
        try {
            Optional<Project> dbProject = Optional.empty();
            if (addUpdateProjectDto.getId() > 0) {
                dbProject = projectRepository.findById(addUpdateProjectDto.getId());
            }
            // Generate slug URL if empty
            if ((addUpdateProjectDto.getSlugURL() == null || addUpdateProjectDto.getSlugURL().isBlank())
                    && addUpdateProjectDto.getProjectName() != null
                    && !addUpdateProjectDto.getProjectName().isBlank()) {

                // If slug is missing/blank → generate from project name
                addUpdateProjectDto.setSlugURL(fileUtils.generateSlug(addUpdateProjectDto.getProjectName()));
            } else if (addUpdateProjectDto.getSlugURL() != null) {

                // If slug is provided → clean/normalize it
                addUpdateProjectDto.setSlugURL(fileUtils.generateSlug(addUpdateProjectDto.getSlugURL()));
            }
            Optional<Project> bySlugURL = Optional.empty();
            if (addUpdateProjectDto.getSlugURL() != null) {
                bySlugURL = projectRepository.findBySlugURL(addUpdateProjectDto.getSlugURL());
            }
            if (addUpdateProjectDto.getCountryId() <= 0) {
                throw new IllegalArgumentException("Country is required");
            }
            if (addUpdateProjectDto.getStateId() <= 0) {
                throw new IllegalArgumentException("State is required");
            }
            // Generating path for storing image
            String projectDir = null;
            if (!addUpdateProjectDto.getSlugURL().isBlank()) {
                projectDir = uploadDir.concat("properties/") + addUpdateProjectDto.getSlugURL();
                fileDestination = projectDir;
            }
            // Process images only if new files are provided
            if (dbProject.isPresent()) {
                Project project = dbProject.get();
                boolean wasPublished = project.isStatus();
                if (bySlugURL.isPresent() && bySlugURL.get().getId() != (addUpdateProjectDto.getId())) {
                    throw new IllegalArgumentException("SlugURL already exists!");
                }
                moveProjectImageDir(project.getSlugURL(), addUpdateProjectDto.getSlugURL());
                if (projectLogo != null && projectDir != null && !projectDir.isBlank()) {
                    fileUtils.deleteFileFromDestination(project.getProjectLogo(), projectDir);
                    if (!fileUtils.isValidAspectRatio(projectLogo.getInputStream(), 792, 203)) {
                        throw new IllegalArgumentException(
                                "Project logo should be of aspect ratio 3:9 or having dimension 792x203 or 390×100");
                    }
                    String savedProjectLogoImageName = processFile(projectLogo, projectDir, 792, 203);
                    project.setProjectLogo(savedProjectLogoImageName); // 792 × 203 px Intrinsic aspect ratio: 792∶203
                    savedFiles.add(savedProjectLogoImageName);
                    project.setProjectThumbnailAltTag(fileUtils.generateImageAltTag(projectLogo));
                }
                if (locationMap != null && projectDir != null && !projectDir.isBlank()) {
                    fileUtils.deleteFileFromDestination(project.getLocationMap(), projectDir); // 300∶193 900 × 579
                    if (!fileUtils.isValidAspectRatio(locationMap.getInputStream(), 815, 813)) {
                        throw new IllegalArgumentException(
                                "Location map image should be of aspect ratio 3:9 or having dimension 900x579 or 600×386");
                    }
                    String projectLocationMapImage = processFile(locationMap, projectDir, 815, 813);
                    project.setLocationMap(projectLocationMapImage);
                    savedFiles.add(projectLocationMapImage);
                    project.setLocationMapAltTag(fileUtils.generateImageAltTag(locationMap));
                }
                if (projectThumbnail != null && projectDir != null && !projectDir.isBlank()) {
                    fileUtils.deleteFileFromDestination(project.getProjectThumbnail(), projectDir);
                    if (!fileUtils.isValidAspectRatio(projectThumbnail.getInputStream(), 600, 600)) {
                        throw new IllegalArgumentException(
                                "Location map image should be of aspect ratio 1:1 or having dimension 600x600 or 400×400");
                    }
                    String projectThumbnailImage = processFile(projectThumbnail, projectDir, 600, 600);
                    project.setProjectThumbnail(projectThumbnailImage); // 600 x 600 1:1
                    savedFiles.add(projectThumbnailImage);
                    project.setProjectThumbnailAltTag(fileUtils.generateImageAltTag(projectThumbnail));
                }
                // save data to database
                List<String> changes = projectChangeSummary(project, addUpdateProjectDto, projectLogo, locationMap, projectThumbnail);
                mapDtoToEntity(project, addUpdateProjectDto);
                projectRepository.save(project);
                listingActivityService.record(
                        ListingActivityService.SOURCE_PROJECT,
                        (long) project.getId(),
                        ListingActivityService.ACTION_UPDATED,
                        ListingActivityService.describeChanges(changes, project.getProjectName()));
                if (wasPublished != project.isStatus()) {
                    listingActivityService.record(
                            ListingActivityService.SOURCE_PROJECT,
                            (long) project.getId(),
                            project.isStatus()
                                    ? ListingActivityService.ACTION_PUBLISHED
                                    : ListingActivityService.ACTION_UNPUBLISHED,
                            project.isStatus() ? "Published" : "Unpublished");
                }
                response.setMessage(Constants.PROJECT_UPDATED);
                response.setIsSuccess(1);
            } else {
                Project newProject = new Project();
                if (bySlugURL.isPresent()) {
                    throw new IllegalArgumentException("SlugURL already exists !");
                }
                if (projectLogo != null) {
                    if (!fileUtils.isValidAspectRatio(projectLogo.getInputStream(), 792, 203)) {
                        throw new IllegalArgumentException(
                                "Project logo should be of aspect ratio 3:9 or having dimension 792x203 or 390×100");
                    }
                    String savedProjectLogoImageName = processFile(projectLogo, projectDir, 792, 203);
                    savedFiles.add(savedProjectLogoImageName);
                    newProject.setProjectLogo(savedProjectLogoImageName);
                }
                if (locationMap != null) {
                    if (!fileUtils.isValidAspectRatio(locationMap.getInputStream(), 815, 813)) {
                        throw new IllegalArgumentException(
                                "Location map image should be of aspect ratio 3:9 or having dimension 900x579 or 600×386");
                    }
                    String projectLocationMapImage = processFile(locationMap, projectDir, 815, 813);
                    newProject.setLocationMap(projectLocationMapImage);
                    savedFiles.add(projectLocationMapImage);
                }
                if (projectThumbnail != null) {
                    if (!fileUtils.isValidAspectRatio(projectThumbnail.getInputStream(), 600, 600)) {
                        throw new IllegalArgumentException(
                                "Location map image should be of aspect ratio 1:1 or having dimension 600x600 or 400×400");
                    }
                    String projectThumbnailImage = processFile(projectThumbnail, projectDir, 600, 600);
                    newProject.setProjectThumbnail(projectThumbnailImage); // 600 x 600 1:1
                    savedFiles.add(projectThumbnailImage);
                }
                mapDtoToEntity(newProject, addUpdateProjectDto);
                User creator = ListingActivityService.currentUserOrNull();
                if (creator != null && newProject.getCreatedBy() == null) {
                    newProject.setCreatedBy(creator);
                }
                Project savedProject = projectRepository.save(newProject);
                listingActivityService.record(
                        ListingActivityService.SOURCE_PROJECT,
                        (long) savedProject.getId(),
                        ListingActivityService.ACTION_CREATED,
                        savedProject.getProjectName());
                if (savedProject.isStatus()) {
                    listingActivityService.record(
                            ListingActivityService.SOURCE_PROJECT,
                            (long) savedProject.getId(),
                            ListingActivityService.ACTION_PUBLISHED,
                            "Published");
                }
                response.setMessage(Constants.PROJECT_SAVED);
                response.setIsSuccess(1);
                response.setProjectId(savedProject.getId());
            }
        } catch (Exception e) {
            // File cleanup if error
            for (String fileName : savedFiles) {
                try {
                    fileUtils.deleteFileFromDestination(fileName, fileDestination); // Pass directory if required
                } catch (Exception ex) {
                    System.err.println("Failed to delete file during rollback: " + fileName);
                }
            }
            response.setMessage(e.getMessage());
            // Re-throw to ensure DB rollback
            throw new RuntimeException(e);
        }
        return response;
    }

    /** Keep existing photos when the admin slug changes. */
    private void moveProjectImageDir(String oldSlug, String newSlug) {
        if (oldSlug == null || newSlug == null) {
            return;
        }
        String from = oldSlug.trim();
        String to = newSlug.trim();
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
            return;
        }
        if (from.contains("..") || to.contains("..")
                || from.contains("/") || to.contains("/")
                || from.contains("\\") || to.contains("\\")) {
            return;
        }
        File oldDir = new File(uploadDir + "properties/" + from);
        File newDir = new File(uploadDir + "properties/" + to);
        if (oldDir.isDirectory() && !newDir.exists() && !oldDir.renameTo(newDir)) {
            log.warn("Could not move project images from {} to {}", from, to);
        }
    }

    // Generating file name and saving it to upload directory
    private String processFile(MultipartFile file, String uploadDir, int width, int height) {
        if (!file.isEmpty()) {
            if (fileUtils.isTypeImage(file)) {
                return fileUtils.saveDesktopImageWithResize(file, uploadDir, width, height, 0.85f);
            }
        }
        return "";
    }

    // Map DTO fields to Project entity
    private void mapDtoToEntity(Project project, AddUpdateProjectDto dto) {
        State selectedState = stateRepository.findById(dto.getStateId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid state selected"));
        if (selectedState.getCountry() == null
                || selectedState.getCountry().getId() != dto.getCountryId()) {
            throw new IllegalArgumentException("Selected state does not belong to the selected country");
        }

        Integer cityId = dto.getCityId();
        if (cityId != null && cityId > 0) {
            cityRepository.findById(cityId).ifPresentOrElse(
                    city -> {
                        if (city.getState() == null || city.getState().getId() != dto.getStateId()) {
                            throw new IllegalArgumentException("Selected city does not belong to the selected state");
                        }
                        project.setCity(city);
                        project.setState(city.getState());
                    },
                    () -> {
                        project.setCity(null);
                        project.setState(selectedState);
                    }
            );
        } else {
            project.setCity(null);
            project.setState(selectedState);
        }
        Optional<Builder> builderObj = Optional.empty();
        if (dto.getBuilderId() > 0) {
            builderObj = builderRepository.findById(dto.getBuilderId());
        }
        Optional<ProjectTypes> projectTypeObj = Optional.empty();
        if (dto.getPropertyTypeId() > 0) {
            projectTypeObj = projectTypeRepository.findById(dto.getPropertyTypeId());
        }
        Optional<ProjectStatus> projectStatus = Optional.empty();
        if (dto.getProjectStatusId() > 0) {
            projectStatus = projectStatusRepository.findById(dto.getProjectStatusId());
        }
        project.setMetaTitle(dto.getMetaTitle());
        project.setMetaDescription(dto.getMetaDescription());
        project.setMetaKeyword(dto.getMetaKeyword());
        project.setProjectName(dto.getProjectName());
        builderObj.ifPresent(project::setBuilder);
        projectTypeObj.ifPresent(project::setProjectTypes);
        project.setProjectLocality(dto.getProjectLocality());
        project.setProjectConfiguration(dto.getProjectConfiguration());
        project.setProjectPrice(String.valueOf(dto.getProjectPrice()));
        project.setIvrNo(dto.getIvrNo());
        project.setReraNo(dto.getReraNo());
        project.setReraWebsite(dto.getReraWebsite());
        projectStatus.ifPresent(project::setProjectStatus);
        project.setSlugURL(dto.getSlugURL());
        project.setShowFeaturedProperties(true);
        project.setAmenityDesc(dto.getAmenityDescription());
        project.setLocationDesc(dto.getLocationDescription());
        project.setFloorPlanDesc(dto.getFloorPlanDescription());
        project.setStatus(dto.isStatus());
    }

    private static List<String> projectChangeSummary(
            Project project,
            AddUpdateProjectDto dto,
            MultipartFile projectLogo,
            MultipartFile locationMap,
            MultipartFile projectThumbnail) {
        List<String> changes = new ArrayList<>();
        ListingActivityService.addIfChanged(changes, "name", project.getProjectName(), dto.getProjectName());
        ListingActivityService.addIfChanged(changes, "price", project.getProjectPrice(), dto.getProjectPrice());
        ListingActivityService.addIfChanged(changes, "locality", project.getProjectLocality(), dto.getProjectLocality());
        ListingActivityService.addIfChanged(changes, "configuration", project.getProjectConfiguration(), dto.getProjectConfiguration());
        ListingActivityService.addIfChanged(changes, "slug", project.getSlugURL(), dto.getSlugURL());
        Integer existingCityId = project.getCity() != null ? project.getCity().getId() : null;
        Integer dtoCityId = dto.getCityId() != null && dto.getCityId() > 0 ? dto.getCityId() : null;
        if (!Objects.equals(existingCityId, dtoCityId)) {
            changes.add("city");
        }
        int existingStateId = project.getState() != null ? project.getState().getId() : 0;
        if (dto.getStateId() > 0 && dto.getStateId() != existingStateId) {
            changes.add("state");
        }
        int builderId = project.getBuilder() != null ? project.getBuilder().getId() : 0;
        if (dto.getBuilderId() > 0 && dto.getBuilderId() != builderId) {
            changes.add("builder");
        }
        int typeId = project.getProjectTypes() != null ? project.getProjectTypes().getId() : 0;
        if (dto.getPropertyTypeId() > 0 && dto.getPropertyTypeId() != typeId) {
            changes.add("property type");
        }
        if (project.isStatus() != dto.isStatus()) {
            changes.add("publish status");
        }
        if (projectLogo != null && !projectLogo.isEmpty()) {
            changes.add("logo");
        }
        if (locationMap != null && !locationMap.isEmpty()) {
            changes.add("location map");
        }
        if (projectThumbnail != null && !projectThumbnail.isEmpty()) {
            changes.add("thumbnail");
        }
        return changes;
    }

    public Set<String> getAllFloorTypes() {
        List<ProjectShortDetails> projects = projectCatalogService.findAllProjects();
        return projects
                .stream()
                .map(ProjectShortDetails::getProjectConfiguration)
                .filter(Objects::nonNull)
                .flatMap(config-> Arrays.stream(config.split(",")))
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    @Transactional
    public List<ProjectShortDetails> getProjectInParts(Integer page, Integer size) {
        List<ProjectShortDetails> allProjects = projectCatalogService.findAllProjects();
        if (allProjects == null || allProjects.isEmpty()) return List.of();
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? 10 : size;
        int start = safePage * safeSize;
        int end = Math.min(start + safeSize, allProjects.size());
        if (start > allProjects.size()) {
            return List.of();
        }
        return allProjects.subList(start, end);
    }

    /**
     * One page of listing cards. Page is 1-based. Gallery files are names only,
     * attached for the current page so listing cards do not call the detail API.
     */
    @Transactional(readOnly = true)
    public ProjectCardPageResponse getProjectListingPage(
            Integer page,
            Integer limit,
            String tab,
            String city,
            String budget,
            String projectStatus,
            String bhkType,
            String configType,
            String quick,
            String sort,
            String query,
            Integer projectId,
            String slug,
            boolean suggest
    ) {
        int safeLimit = suggest
                ? Math.min(Math.max(limit == null ? 8 : limit, 1), 24)
                : Math.min(Math.max(limit == null ? 10 : limit, 1), 24);
        int safePage = page == null || page < 1 ? 1 : page;

        List<ProjectShortDetails> context = new ArrayList<>();
        for (ProjectShortDetails project : publicProjects()) {
            if (!matchesListingContext(project, tab, city, budget, projectStatus)) continue;
            context.add(project);
        }

        Map<String, Integer> quickCounts = new LinkedHashMap<>();
        quickCounts.put("ready", 0);
        quickCounts.put("new", 0);
        quickCounts.put("under-construction", 0);
        quickCounts.put("ultra-luxury", 0);
        for (ProjectShortDetails project : context) {
            if (!matchesBhk(project.getProjectConfiguration(), bhkType)) continue;
            if (!matchesConfig(project.getProjectConfiguration(), configType)) continue;
            String statusNorm = norm(project.getProjectStatusName());
            for (String key : quickCounts.keySet()) {
                if (matchesQuick(statusNorm, key)) {
                    quickCounts.put(key, quickCounts.get(key) + 1);
                }
            }
        }

        List<ProjectShortDetails> filtered = new ArrayList<>();
        for (ProjectShortDetails project : context) {
            if (!matchesQuick(norm(project.getProjectStatusName()), quick)) continue;
            if (suggest) {
                if (!matchesQuery(project, query)) continue;
                filtered.add(project);
                continue;
            }
            if (!matchesBhk(project.getProjectConfiguration(), bhkType)) continue;
            if (!matchesConfig(project.getProjectConfiguration(), configType)) continue;
            if (!matchesQuery(project, query)) continue;
            if (projectId != null && projectId > 0 && project.getId() != projectId) continue;
            if (slug != null && !slug.isBlank()
                    && !slug.equalsIgnoreCase(String.valueOf(project.getSlugURL()))) {
                continue;
            }
            filtered.add(project);
        }

        if (suggest && filtered.isEmpty() && !norm(query).isEmpty()) {
            for (ProjectShortDetails project : context) {
                if (!matchesQuick(norm(project.getProjectStatusName()), quick)) continue;
                if (fuzzyMatchesQuery(project, query)) filtered.add(project);
            }
        }

        if (suggest && (sort == null || sort.isBlank())) {
            rankSuggestions(filtered, query);
        } else {
            sortProjects(filtered, sort);
        }

        ProjectCardPageResponse response = new ProjectCardPageResponse();
        response.setPage(suggest ? 1 : safePage);
        response.setLimit(safeLimit);
        response.setTotal(filtered.size());
        response.setTotalPages(Math.max(1, (int) Math.ceil(filtered.size() / (double) safeLimit)));
        response.setQuickFilterCounts(quickCounts);

        int from = suggest ? 0 : Math.min((safePage - 1) * safeLimit, filtered.size());
        int to = Math.min(from + safeLimit, filtered.size());
        List<ProjectListingCardDto> cards = new ArrayList<>();
        for (ProjectShortDetails project : filtered.subList(from, to)) {
            cards.add(ProjectListingCardDto.fromShort(project));
        }
        if (!suggest) {
            attachCardExtras(cards);
        }
        response.setProjects(cards);
        return response;
    }

    private List<ProjectShortDetails> publicProjects() {
        List<ProjectShortDetails> projects = projectCatalogService.findAllProjects();
        if (projects == null || projects.isEmpty()) return List.of();
        List<ProjectShortDetails> visible = new ArrayList<>();
        for (ProjectShortDetails project : projects) {
            if (!isHiddenPublicProject(project)) visible.add(project);
        }
        return visible;
    }

    private void attachCardExtras(List<ProjectListingCardDto> cards) {
        if (cards == null || cards.isEmpty()) return;
        List<Integer> ids = cards.stream().map(ProjectListingCardDto::getId).toList();
        Map<Integer, List<String>> imagesByProject = new HashMap<>();
        for (Object[] row : projectGalleryRepository.findImageNamesByProjectIds(ids)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            int projectId = ((Number) row[0]).intValue();
            String image = String.valueOf(row[1]).trim();
            if (image.isEmpty()) continue;
            imagesByProject.computeIfAbsent(projectId, ignored -> new ArrayList<>()).add(image);
        }
        Map<Integer, List<LocationBenefitDto>> benefitsByProject = new HashMap<>();
        for (Object[] row : locationBenefitRepository.findCardBenefitsByProjectIds(ids)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            int projectId = ((Number) row[0]).intValue();
            String name = String.valueOf(row[1]).trim();
            if (name.isEmpty()) continue;
            String distance = row.length > 2 && row[2] != null ? String.valueOf(row[2]) : "";
            benefitsByProject
                    .computeIfAbsent(projectId, ignored -> new ArrayList<>())
                    .add(new LocationBenefitDto(name, distance));
        }
        for (ProjectListingCardDto card : cards) {
            card.setGalleryImageNames(imagesByProject.getOrDefault(card.getId(), List.of()));
            card.setLocationBenefits(benefitsByProject.getOrDefault(card.getId(), List.of()));
        }
    }

    private static boolean isHiddenPublicProject(ProjectShortDetails project) {
        if (project == null) return true;
        String slug = norm(project.getSlugURL());
        String builderSlug = norm(project.getBuilderSlug());
        String builder = norm(project.getBuilderName());
        if (slug.startsWith("ashiana-")) return true;
        if ("ashiana-housing".equals(builderSlug)) return true;
        return "ashiana housing".equals(builder) || "ashiana".equals(builder) || builder.startsWith("ashiana");
    }

    private static boolean matchesListingContext(
            ProjectShortDetails project,
            String tab,
            String city,
            String budget,
            String projectStatus
    ) {
        String typeNorm = norm(project.getPropertyTypeName());
        String tabNorm = norm(tab);
        if ("residential".equals(tabNorm) && !typeNorm.contains("residential")) return false;
        if ("commercial".equals(tabNorm) && !typeNorm.contains("commercial")) return false;
        if (!matchesCity(project, city)) return false;
        if (!matchesBudget(project.getProjectPrice(), budget)) return false;
        String statusFilter = norm(projectStatus);
        if (!statusFilter.isEmpty() && !norm(project.getProjectStatusName()).contains(statusFilter)) return false;
        return true;
    }

    private static boolean matchesCity(ProjectShortDetails project, String cityFilter) {
        String filter = norm(cityFilter);
        if (filter.isEmpty()) return true;
        String city = norm(project.getCityName());
        if ("noida".equals(filter) && (city.contains("noida extension") || city.contains("greater noida"))) {
            return false;
        }
        List<String> names = switch (filter) {
            case "gurugram", "gurgaon" -> List.of("gurugram", "gurgaon");
            case "bangalore", "bengaluru" -> List.of("bangalore", "bengaluru");
            case "delhi", "new delhi" -> List.of("delhi", "new delhi");
            default -> List.of(filter);
        };
        return names.contains(city);
    }

    private static boolean matchesBudget(String rawPrice, String budget) {
        String label = norm(budget).replace("*", "");
        if (label.isEmpty()) return true;
        Double crore = priceToCrore(rawPrice);
        if (crore == null) return false;
        if (label.contains("50") && (label.contains("under") || label.contains("upto") || label.contains("up to"))) {
            return crore <= 0.5d;
        }
        if (label.contains("above") && label.contains("5")) return crore >= 5d;
        if (label.contains("3") && label.contains("5")) return crore >= 3d && crore < 5d;
        if (label.contains("1") && label.contains("3")) return crore >= 1d && crore < 3d;
        if ((label.contains("up to") || label.contains("upto")) && label.contains("1")) return crore <= 1d;
        return true;
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?(?:\\d+\\.?\\d*|\\.\\d+)");

    private static Double priceToCrore(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.replace(",", "").toLowerCase(Locale.ROOT);
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        double value;
        try {
            value = Double.parseDouble(matcher.group());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (text.contains("lakh") || text.contains("lac") || text.matches(".*\\d\\s*l(?:s)?(?:\\s|$).*")) {
            return value / 100d;
        }
        if (text.contains("crore") || text.matches(".*\\bcr\\b.*")) return value;
        if (value >= 100000d) return value / 10000000d;
        return value;
    }

    private static boolean matchesBhk(String config, String wantedRaw) {
        String wanted = wantedRaw == null ? "" : wantedRaw.trim();
        if (wanted.isEmpty()) return true;
        if (config == null || config.isBlank()) return false;
        String c = config.toLowerCase(Locale.ROOT);
        String w = wanted.toLowerCase(Locale.ROOT);
        if (w.contains("rk")) return c.contains("rk") || c.contains("studio");
        if (w.equals("villa")) return c.matches("(?s).*\\bvilla\\b.*");
        if (w.equals("plot") || w.equals("plots")) return c.matches("(?s).*\\bplots?\\b.*");
        if (w.matches("5\\+\\s*bhk")) {
            Matcher matcher = Pattern.compile("(\\d+)\\s*bhk").matcher(c);
            while (matcher.find()) {
                if (Integer.parseInt(matcher.group(1)) >= 5) return true;
            }
            return false;
        }
        Matcher bhk = Pattern.compile("^(\\d+)\\s*bhk").matcher(w);
        if (bhk.find()) {
            return c.matches("(?s).*\\b" + bhk.group(1) + "\\s*bhk\\b.*");
        }
        return c.contains(w);
    }

    private static boolean matchesConfig(String config, String keyRaw) {
        String key = keyRaw == null ? "" : keyRaw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return true;
        if (config == null || config.isBlank()) return false;
        String c = config.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "shops", "shop" -> c.matches("(?s).*\\bshops?\\b.*");
            case "office" -> c.contains("office");
            case "kiosk" -> c.contains("kiosk");
            case "food-court", "food court" -> c.contains("food court");
            case "restaurant" -> c.contains("restaurant");
            case "showroom" -> c.contains("showroom");
            case "sco-plots", "sco plots" -> c.contains("sco plot");
            default -> c.contains(key.replace('-', ' '));
        };
    }

    private static boolean matchesQuick(String statusNorm, String key) {
        if (key == null || key.isBlank()) return true;
        String status = statusNorm == null ? "" : statusNorm;
        return switch (key) {
            case "ready" -> status.contains("ready") || status.contains("completed");
            case "new" -> status.contains("new launch") || status.contains("new launched");
            case "under-construction" -> status.contains("under construction");
            case "ultra-luxury" -> status.contains("ultra luxury");
            default -> true;
        };
    }

    private static boolean matchesQuery(ProjectShortDetails project, String query) {
        String q = norm(query);
        if (q.isEmpty()) return true;
        String haystack = norm(String.join(" ",
                safe(project.getProjectName()),
                safe(project.getBuilderName()),
                safe(project.getCityName()),
                safe(project.getProjectLocality()),
                safe(project.getProjectAddress()),
                safe(project.getProjectConfiguration())
        ));
        for (String token : q.split(" ")) {
            if (token.isEmpty() || haystack.contains(token)) continue;
            String alias = QUERY_TOKEN_ALIASES.get(token);
            if (alias == null || !haystack.contains(alias)) return false;
        }
        return true;
    }

    private static final Map<String, String> QUERY_TOKEN_ALIASES = Map.of(
            "gurgaon", "gurugram",
            "gurugram", "gurgaon",
            "bangalore", "bengaluru",
            "bengaluru", "bangalore",
            "bombay", "mumbai"
    );

    /** Typo-tolerant match: every query token (3+ chars) is within one edit of a word in the name, builder, city or locality. */
    private static boolean fuzzyMatchesQuery(ProjectShortDetails project, String query) {
        String q = norm(query);
        if (q.length() < 3) return false;
        String[] words = norm(String.join(" ",
                safe(project.getProjectName()),
                safe(project.getBuilderName()),
                safe(project.getCityName()),
                safe(project.getProjectLocality())
        )).split("[^a-z0-9]+");
        for (String token : q.split(" ")) {
            if (token.length() < 3) continue;
            boolean found = false;
            for (String word : words) {
                if (word.isEmpty()) continue;
                if (word.startsWith(token) || withinOneEdit(token, word)
                        || (word.length() > token.length() && withinOneEdit(token, word.substring(0, token.length())))) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean withinOneEdit(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        int i = 0;
        int j = 0;
        int edits = 0;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++edits > 1) return false;
            if (la > lb) i++;
            else if (lb > la) j++;
            else {
                i++;
                j++;
            }
        }
        return edits + (la - i) + (lb - j) <= 1;
    }

    /** Name prefix matches first, then name matches, then builder / city / locality matches. Stable within a tier. */
    private static void rankSuggestions(List<ProjectShortDetails> projects, String query) {
        String q = norm(query);
        if (q.isEmpty() || projects.size() < 2) return;
        projects.sort(Comparator.comparingInt((ProjectShortDetails project) -> {
            String name = norm(project.getProjectName());
            if (name.equals(q)) return 0;
            if (name.startsWith(q)) return 1;
            if (name.contains(" " + q) || name.contains(q)) return 2;
            if (norm(project.getBuilderName()).contains(q)) return 3;
            if (norm(project.getProjectLocality()).contains(q)) return 4;
            if (norm(project.getCityName()).contains(q)) return 5;
            return 6;
        }));
    }

    private static void sortProjects(List<ProjectShortDetails> projects, String sort) {
        String key = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "price-low" -> projects.sort(Comparator.comparing(
                    (ProjectShortDetails project) -> priceToCrore(project.getProjectPrice()),
                    Comparator.nullsLast(Double::compareTo)));
            case "price-high" -> projects.sort(Comparator.comparing(
                    (ProjectShortDetails project) -> priceToCrore(project.getProjectPrice()),
                    Comparator.nullsLast(Comparator.reverseOrder())));
            case "newest" -> projects.sort(Comparator.comparing(
                    ProjectShortDetails::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            default -> {
                // findAllProjects is already ordered by project name.
            }
        }
    }

    private static String norm(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public List<ProjectShortDetails> getAllProjects() {
        return projectCatalogService.findAllProjects();
    }

    @Transactional(readOnly = true)
    public List<ProjectExportDto> findAllForExcelExport() {
        return projectRepository.findAllForExcelExport();
    }

    public ProjectFullDetails getProjectDetailsBySlug(String slug) {
        ProjectFullDetails projectFullDetails = projectRepository.findProjectFullDetails(slug).orElseThrow(() -> new IllegalArgumentException("Project not found with slug: "+ slug));
        return enrichProjectFullDetails(projectFullDetails);
    }

    public ProjectFullDetails getProjectDetailsBySlugForAdmin(String slug) {
        ProjectFullDetails projectFullDetails = projectRepository.findProjectFullDetailsForAdmin(slug)
                .orElseThrow(() -> new IllegalArgumentException("Project not found with slug: " + slug));
        return enrichProjectFullDetails(projectFullDetails);
    }

    public ProjectFullDetails getProjectDetailsByIdForAdmin(int id) {
        ProjectFullDetails projectFullDetails = projectRepository.findProjectFullDetailsByIdForAdmin(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found with id: " + id));
        return enrichProjectFullDetails(projectFullDetails);
    }

    private ProjectFullDetails enrichProjectFullDetails(ProjectFullDetails projectFullDetails) {
        List<Amenity> amenities = amenityRepository.findByProjectsId(projectFullDetails.getId());
        List<AmenityDto> amenityDtoList = amenities.stream().map(amenity-> AmenityDto.builder()
                .id(amenity.getId())
                .title(amenity.getTitle())
                .altTag(amenity.getAltTag())
                .image(amenity.getAmenityImageUrl())
                .build()).toList();
        projectFullDetails.setAmenities(amenityDtoList);
        projectFullDetails.setDesktopImages(desktopBannerRepository.findByProjectId(projectFullDetails.getId()));
        projectFullDetails.setMobileImages(mobileBannerRepository.findByProjectId(projectFullDetails.getId()));
        projectFullDetails.setFloorPlans(floorPlanRepository.findByProjectId(projectFullDetails.getId()));
        projectFullDetails.setGalleryImages(projectGalleryRepository.findByProjectId(projectFullDetails.getId()));
        projectFullDetails.setLocationBenefits(locationBenefitRepository.findByProjectId(projectFullDetails.getId()));
        projectFullDetails.setFaqs(faqsRepository.findByProjectId(projectFullDetails.getId()));
        return projectFullDetails;
    }
}
