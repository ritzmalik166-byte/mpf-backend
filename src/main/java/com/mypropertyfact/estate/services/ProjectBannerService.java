package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.common.CommonMapper;
import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.dtos.ProjectBannerDto;
import com.mypropertyfact.estate.dtos.ProjectDetailDto;
import com.mypropertyfact.estate.dtos.ProjectDesktopBannerDto;
import com.mypropertyfact.estate.dtos.ProjectMobileBannerDto;
import com.mypropertyfact.estate.entities.Project;
import com.mypropertyfact.estate.entities.ProjectBanner;
import com.mypropertyfact.estate.entities.ProjectDesktopBanner;
import com.mypropertyfact.estate.entities.ProjectMobileBanner;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.ProjectBannerRepository;
import com.mypropertyfact.estate.repositories.ProjectDesktopBannerRepository;
import com.mypropertyfact.estate.repositories.ProjectMobileBannerRepository;
import com.mypropertyfact.estate.repositories.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProjectBannerService {

    private final ProjectBannerRepository projectBannerRepository;

    private final ProjectRepository projectRepository;

    private final FileUtils fileUtils;

    private final ProjectMobileBannerRepository projectMobileBannerRepository;

    private final ProjectDesktopBannerRepository projectDesktopBannerRepository;

    @Value("${uploads_path}")
    private String uploadDir;

    private final CommonMapper commonMapper;

    /**
     * Banner manager list. Previously this mapped the FULL detail (floor plans, amenities, gallery, FAQs...) of every
     * project, which fired thousands of lazy queries and timed out with a 500. Now it runs 4 flat queries and only
     * fills the fields the banner page uses: id, projectName, slugURL and the three banner lists.
     */
    @Transactional(readOnly = true)
    public List<ProjectDetailDto> getAllBanners() {
        Map<Integer, List<ProjectMobileBannerDto>> mobile = new HashMap<>();
        for (ProjectMobileBannerDto m : projectMobileBannerRepository.findAllBannerDtos()) {
            mobile.computeIfAbsent(m.getProjectId(), k -> new ArrayList<>()).add(m);
        }
        Map<Integer, List<ProjectDesktopBannerDto>> desktop = new HashMap<>();
        for (ProjectDesktopBannerDto d : projectDesktopBannerRepository.findAllBannerDtos()) {
            desktop.computeIfAbsent(d.getProjectId(), k -> new ArrayList<>()).add(d);
        }
        Map<Integer, List<Object[]>> legacy = new HashMap<>();
        for (Object[] row : projectBannerRepository.findAllLite()) {
            legacy.computeIfAbsent((Integer) row[1], k -> new ArrayList<>()).add(row);
        }

        List<ProjectDetailDto> result = new ArrayList<>();
        for (Object[] p : projectRepository.findIdNameSlugOrderByName()) {
            int id = (Integer) p[0];
            String name = (String) p[1];
            String slug = (String) p[2];
            ProjectDetailDto dto = new ProjectDetailDto();
            dto.setId(id);
            dto.setProjectName(name);
            dto.setSlugURL(slug);
            dto.setProjectMobileBannerDtoList(mobile.getOrDefault(id, List.of()));
            dto.setProjectDesktopBannerDtoList(desktop.getOrDefault(id, List.of()));
            List<ProjectBannerDto> bannerList = new ArrayList<>();
            for (Object[] b : legacy.getOrDefault(id, List.of())) {
                ProjectBannerDto bd = new ProjectBannerDto();
                if (b[2] != null) {
                    bd.setProjectId(id);
                    bd.setProjectName(name);
                    bd.setSlugURL(slug);
                    bd.setAltTag((String) b[4]);
                    bd.setId((Integer) b[0]);
                    bd.setProjectDesktopBanner((String) b[2]);
                    bd.setProjectDesktopBannerImageList(new ArrayList<>());
                    bd.setProjectMobileBannerImageList(new ArrayList<>());
                    bd.setDeletedDesktopImageIds(new ArrayList<>());
                    bd.setDeletedMobileImageIds(new ArrayList<>());
                }
                if (b[3] != null) bd.setProjectMobileBanner((String) b[3]);
                bannerList.add(bd);
            }
            dto.setProjectBannerList(bannerList);
            result.add(dto);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Response postBanner(ProjectBannerDto projectBannerDto) {
        Response response = new Response();
        List<String> savedFiles = new ArrayList<>();
        String imageLocationPath = null;
        try {
            String destination;
            Optional<Project> project = projectRepository.findById(projectBannerDto.getProjectId());
            destination = project.map(value -> uploadDir.concat(value.getSlugURL()).concat("/")).orElse(null);
            imageLocationPath = destination;
            if (projectBannerDto.getProjectId() == 0) {
                throw new IllegalArgumentException("All fields are required!");
            }

            // Save new mobile banners
            if (projectBannerDto.getProjectMobileBannerImageList() != null) {
                for (MultipartFile file : projectBannerDto.getProjectMobileBannerImageList()) {
                    if (file != null) {
                        if (!fileUtils.isTypeImage(file)) {
                            throw new IllegalArgumentException("File should be type of image only!");
                        }
                        ProjectMobileBanner banner = new ProjectMobileBanner();
                        project.ifPresent(banner::setProject);
                        banner.setMobileAltTag(fileUtils.generateImageAltTag(file));
                        if (destination != null) {
                            String savedFileName = fileUtils.saveDesktopImageWithResize(file, destination, 600, 600, 0.85f);
                            savedFiles.add(savedFileName); // track file for rollback
                            banner.setMobileImage(savedFileName);
                        }

                        projectMobileBannerRepository.save(banner);
                    }
                }
            }

            // Save new desktop banners
            if (projectBannerDto.getProjectDesktopBannerImageList() != null) {
                for (MultipartFile file : projectBannerDto.getProjectDesktopBannerImageList()) {
                    if (file != null) {
                        if (!fileUtils.isTypeImage(file)) {
                            throw new IllegalArgumentException("File should be type of image only!");
                        }
                        try {
                            if (!fileUtils.isValidAspectRatio(file.getInputStream(), 2225f, 1065f)) {
                                throw new IllegalArgumentException(
                                        "Desktop image must have an aspect ratio close to 2.17:1 " +
                                                "(e.g., 2225×1065 px or any proportional size like 1920×885 px)"
                                );
                            }
                        }catch (Exception ex){
                            throw new IllegalArgumentException(ex.getMessage());
                        }
                        ProjectDesktopBanner banner = new ProjectDesktopBanner();
                        project.ifPresent(banner::setProject);
                        banner.setDesktopAltTag(fileUtils.generateImageAltTag(file));
                        if (destination != null) {
                            String savedFileName = fileUtils.saveDesktopImageWithResize(file, destination, 1920, 600, 0.85f);
                            savedFiles.add(savedFileName); // track file for rollback
                            banner.setDesktopImage(savedFileName);
                        }

                        projectDesktopBannerRepository.save(banner);
                    }
                }
            }
            // Delete old mobile images
            if (projectBannerDto.getDeletedMobileImageIds() != null) {
                for (Integer id : projectBannerDto.getDeletedMobileImageIds()) {
                    projectMobileBannerRepository.findById(id).ifPresent(banner -> {
                        if (banner.getMobileImage() != null && !banner.getMobileImage().isBlank() && destination != null) {
                            fileUtils.deleteFileFromDestination(banner.getMobileImage(), destination);
                        }
                    });
                }
                projectMobileBannerRepository.deleteAllById(projectBannerDto.getDeletedMobileImageIds());
            }
            // Delete old desktop images
            if (projectBannerDto.getDeletedDesktopImageIds() != null) {
                for (Integer id : projectBannerDto.getDeletedDesktopImageIds()) {
                    projectDesktopBannerRepository.findById(id).ifPresent(banner -> {
                        if (banner.getDesktopImage() != null && !banner.getDesktopImage().isBlank() && destination != null) {
                            fileUtils.deleteFileFromDestination(banner.getDesktopImage(), destination);
                        }
                    });
                }
                projectDesktopBannerRepository.deleteAllById(projectBannerDto.getDeletedDesktopImageIds());
            }
            response.setMessage("Banner images saved successfully!");
            response.setIsSuccess(1);
        } catch (Exception ex) {
            // Rollback DB automatically happens because of @Transactional
            // Delete any saved files
            for (String filePath : savedFiles) {
                try {
                    fileUtils.deleteFileFromDestination(filePath, imageLocationPath);
                } catch (Exception e) {
                    // log error but don't override main exception
                }
            }
            throw ex; // rethrow so Spring triggers rollback
        }
        return response;
    }

    public ProjectBanner getBySlug(String url) {
        return this.projectBannerRepository.getBySlug(url);
    }

    public Response deleteBanner(int id) {
        Response response = new Response();
        try {
            Optional<ProjectBanner> byId = projectBannerRepository.findById(id);
            byId.ifPresent(p -> {
                if (p.getProject() != null) {
                    fileUtils.deleteFileFromDestination(p.getMobileBanner(), uploadDir + p.getProject().getSlugURL());
                    fileUtils.deleteFileFromDestination(p.getDesktopBanner(), uploadDir + p.getProject().getSlugURL());
                }
                projectBannerRepository.deleteById(id);
            });
            return new Response(1, "Banner deleted successfully...", 0);
        } catch (Exception e) {
            response.setMessage(e.getMessage());
        }
        return response;
    }

}
