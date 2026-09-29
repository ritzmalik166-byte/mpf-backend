package com.mypropertyfact.estate.controllers;

import com.mypropertyfact.estate.dtos.AddUpdateProjectDto;
import com.mypropertyfact.estate.dtos.ProjectExportDto;
import com.mypropertyfact.estate.dtos.ProjectCardPageResponse;
import com.mypropertyfact.estate.dtos.ProjectShortDetails;
import com.mypropertyfact.estate.models.ProjectAmenityDto;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.services.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/projects")
@Slf4j
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ResponseEntity<List<ProjectShortDetails>> getShortDetails() {
        return new ResponseEntity<>(projectService.getAllProjects(), HttpStatus.OK);
    }

    /**
     * Full project rows for admin Excel export (ids, SEO, HTML descriptions, RERA, timestamps, etc.).
     */
    @GetMapping("/admin-export")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')")
    public ResponseEntity<List<ProjectExportDto>> adminExportForExcel() {
        return new ResponseEntity<>(projectService.findAllForExcelExport(), HttpStatus.OK);
    }

    @PostMapping("/add-new")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')")
    public ResponseEntity<Response> saveProject(
            @RequestParam(required = false) MultipartFile projectLogo,
            @RequestParam(required = false) MultipartFile locationMap,
            @RequestParam(required = false) MultipartFile projectThumbnail,
            @RequestPart("addUpdateProjectDto") AddUpdateProjectDto addUpdateProjectDto) {
        return new ResponseEntity<>(this.projectService.saveProject(
                projectLogo,
                locationMap,
                projectThumbnail,
                addUpdateProjectDto
        ), HttpStatus.OK);
    }

    @GetMapping("/get/{url}")
    public ResponseEntity<?> getBySlug(@PathVariable("url") String url) {
        return new ResponseEntity<>(this.projectService.getProjectDetailsBySlug(url), HttpStatus.OK);
    }

    @GetMapping("/admin/get/{url}")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')")
    public ResponseEntity<?> getBySlugForAdmin(@PathVariable("url") String url) {
        return new ResponseEntity<>(this.projectService.getProjectDetailsBySlugForAdmin(url), HttpStatus.OK);
    }

    @GetMapping("/admin/get-by-id/{id}")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')")
    public ResponseEntity<?> getByIdForAdmin(@PathVariable("id") int id) {
        return new ResponseEntity<>(this.projectService.getProjectDetailsByIdForAdmin(id), HttpStatus.OK);
    }

    @DeleteMapping("/delete/{id}")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')")
    public ResponseEntity<Response> deleteProject(@PathVariable("id") int id) {
        return new ResponseEntity<>(this.projectService.deleteProject(id), HttpStatus.OK);
    }

    @GetMapping("/search-by-type-city-budget")
    public ResponseEntity<?> searchByPropertyTypeLocationBudget(@RequestParam("propertyType") String propertyType,
                                                                @RequestParam("propertyLocation") String propertyLocation,
                                                                @RequestParam("budget") String budget,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "9") int limit) {
        return new ResponseEntity<>(projectService.searchByPropertyTypeLocationBudget(propertyType, propertyLocation, budget), HttpStatus.OK);
    }

    @PostMapping("/add-update-amenity")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')")
    public ResponseEntity<Response> addUpdateAmenity(@RequestBody ProjectAmenityDto projectAmenityDto) {
        return new ResponseEntity<>(projectService.addUpdateAmenity(projectAmenityDto), HttpStatus.OK);
    }

    @GetMapping("/all-floor-types")
    public ResponseEntity<Set<String>> getAllFloorTypes() {
        return ResponseEntity.ok(projectService.getAllFloorTypes());
    }

    /**
     * Legacy slice: {@code page} is 0-based and {@code size} is the page length.
     * Listing cards: {@code page=1&limit=10} (1-based) returns one page of card fields.
     */
    @GetMapping("/get-projects-in-parts")
    public ResponseEntity<?> getProjectInParts(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "budget", required = false) String budget,
            @RequestParam(value = "projectStatus", required = false) String projectStatus,
            @RequestParam(value = "bhkType", required = false) String bhkType,
            @RequestParam(value = "configType", required = false) String configType,
            @RequestParam(value = "quick", required = false) String quick,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "projectId", required = false) Integer projectId,
            @RequestParam(value = "slug", required = false) String slug,
            @RequestParam(value = "suggest", defaultValue = "false") boolean suggest
    ) {
        boolean listingRequest = limit != null || suggest || hasListingFilter(
                tab, city, budget, projectStatus, bhkType, configType, quick, sort, query, projectId, slug);
        if (!listingRequest) {
            return ResponseEntity.ok(projectService.getProjectInParts(page == null ? 0 : page, size));
        }
        ProjectCardPageResponse body = projectService.getProjectListingPage(
                page,
                limit != null ? limit : size,
                tab,
                city,
                budget,
                projectStatus,
                bhkType,
                configType,
                quick,
                sort,
                query,
                projectId,
                slug,
                suggest
        );
        return ResponseEntity.ok(body);
    }

    private static boolean hasListingFilter(
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
            String slug
    ) {
        return isPresent(tab) || isPresent(city) || isPresent(budget) || isPresent(projectStatus)
                || isPresent(bhkType) || isPresent(configType) || isPresent(quick) || isPresent(sort)
                || isPresent(query) || (projectId != null && projectId > 0) || isPresent(slug);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    @GetMapping("/get-by-slug/{slug}")
    public ResponseEntity<?> getProjectDetailsBySlug(@PathVariable("slug") String slug) {
        return new ResponseEntity<>(projectService.getProjectDetailsBySlug(slug), HttpStatus.OK);
    }
}
