package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.ProjectMobileBannerDto;
import com.mypropertyfact.estate.entities.Project;
import com.mypropertyfact.estate.entities.ProjectMobileBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectMobileBannerRepository extends JpaRepository<ProjectMobileBanner, Integer> {
    List<ProjectMobileBanner> findByProject(Project project);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectMobileBannerDto(
            pdb.mobileImage,
            pdb.mobileAltTag
            )
            FROM ProjectMobileBanner pdb
            WHERE pdb.project.id = :projectId
            """)
    List<ProjectMobileBannerDto> findByProjectId(@Param("projectId") int projectId);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectMobileBannerDto(
            b.id, p.id, p.projectName, p.slugURL, b.mobileAltTag, b.mobileImage)
            FROM ProjectMobileBanner b JOIN b.project p
            """)
    List<ProjectMobileBannerDto> findAllBannerDtos();
}
