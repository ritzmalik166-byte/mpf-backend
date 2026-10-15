package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.dtos.ProjectShortDetails;
import com.mypropertyfact.estate.dtos.ProjectTypeDto;
import com.mypropertyfact.estate.entities.ProjectTypes;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.projections.ProjectTypeView;
import com.mypropertyfact.estate.repositories.ProjectRepository;
import com.mypropertyfact.estate.repositories.ProjectTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectTypesService {
    
    private final ProjectTypeRepository projectTypeRepository;
    
    private final ProjectRepository projectRepository;
    
    private final ProjectCatalogService projectCatalogService;

    public List<ProjectTypeView> getAllProjectTypes() {
        return this.projectTypeRepository.findAllProjectedBy(Sort.by(Sort.Direction.ASC, "projectTypeName"));
    }

    public Response addUpdateProjectType(ProjectTypes projectTypes) {
        Response response = new Response();
        try {
            if (projectTypes == null || projectTypes.getProjectTypeName().isEmpty()) {
                response.setMessage("Project Type is required !");
                return response;
            }
            ProjectTypes existingData = this.projectTypeRepository.findByProjectTypeName(projectTypes.getProjectTypeName());
            if (existingData != null && existingData.getId() != projectTypes.getId()) {
                response.setMessage("Project type already exists...");
                return response;
            }
            String slugUrl = projectTypes.getProjectTypeName().toLowerCase();
            String[] words = slugUrl.split(" "); // Split the string by spaces
            StringBuilder result = new StringBuilder();

            // Iterate over the words
            for (int i = 0; i < words.length; i++) {
                result.append(words[i]); // Add the current word to the result
                // If it's not the last word, add a hyphen
                if (i < words.length - 1) {
                    result.append("-");
                }
            }
            // The result is the slug URL
            String finalSlug = result.toString();
            projectTypes.setSlugUrl(finalSlug);


            if (projectTypes.getId() > 0) {
                ProjectTypes dbProjectTypes = this.projectTypeRepository.findById(projectTypes.getId()).orElseThrow(()-> new IllegalArgumentException("Project type not found with id: "+ projectTypes.getId()));
                dbProjectTypes.setProjectTypeName(projectTypes.getProjectTypeName());
                dbProjectTypes.setSlugUrl(projectTypes.getSlugUrl());
                dbProjectTypes.setProjectTypeDesc(projectTypes.getProjectTypeDesc());
                dbProjectTypes.setMetaDesc(projectTypes.getMetaDesc());
                dbProjectTypes.setMetaKeyword(projectTypes.getMetaKeyword());
                dbProjectTypes.setMetaTitle(projectTypes.getMetaTitle());
                this.projectTypeRepository.save(dbProjectTypes);
                response.setIsSuccess(1);
                response.setMessage("Project type updated successfully...");
            } else {
                this.projectTypeRepository.save(projectTypes);
                response.setMessage("Project type added successfully...");
                response.setIsSuccess(1);
            }
        } catch (Exception e) {
            response.setMessage(e.getMessage());
        }
        return response;
    }

    @Transactional
    public ProjectTypeDto getBySlug(String url) {
        ProjectTypeDto projectTypeDetailDto = new ProjectTypeDto();
        Optional<ProjectTypes> projectTypeData = this.projectTypeRepository.findBySlugUrl(url);
        List<ProjectShortDetails> projectDetailDtoList = projectCatalogService.findAllProjects();
            projectTypeData.ifPresent(projectType -> {
            projectTypeDetailDto.setId(projectType.getId());
            projectTypeDetailDto.setProjectTypeName(projectType.getProjectTypeName());
            projectTypeDetailDto.setProjectTypeDescription(projectType.getProjectTypeDesc());
            projectTypeDetailDto.setMetaTitle(projectType.getMetaTitle());
            projectTypeDetailDto.setMetaKeywords(projectType.getMetaKeyword());
            projectTypeDetailDto.setMetaDescription(projectType.getMetaDesc());
        });
        if(url.equals("new-launches")) {
            projectDetailDtoList = projectDetailDtoList.stream().filter(project -> project.getProjectStatusName().trim().toLowerCase().equals("new launched".toLowerCase().trim())).collect(Collectors.toList());
        } else {
            projectDetailDtoList = projectDetailDtoList.stream().filter(project -> project.getPropertyTypeName().trim().toLowerCase().equals(url.toLowerCase().trim())).collect(Collectors.toList());
        }
        projectTypeDetailDto.setProjectList(projectDetailDtoList);
        return projectTypeDetailDto;
    }

//    public List<Project> getPropertiesBySlug(String url) {
//        ProjectTypes projectTypes = this.projectTypeRepository.findBySlugUrl(url);
//        List<Project> projects = this.projectRepository.getAllProjectsByType(projectTypes.getId());
//        return projects;
//    }

    public Response deleteProjectType(int id) {
        this.projectTypeRepository.deleteById(id);
        return new Response(1, "Project type deleted successfully...", 0);
    }

    public List<ProjectTypes> getAllProjectTypesList() {
        return projectTypeRepository.findAll();
    }
}
