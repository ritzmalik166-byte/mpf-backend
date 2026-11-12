package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.ProjectDesktopBannerDto;
import com.mypropertyfact.estate.entities.Project;
import com.mypropertyfact.estate.entities.ProjectDesktopBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectDesktopBannerRepository extends JpaRepository<ProjectDesktopBanner, Integer> {
    List<ProjectDesktopBanner> findByProject(Project project);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectDesktopBannerDto(
            pdb.desktopImage,
            pdb.desktopAltTag
            )
            FROM ProjectDesktopBanner pdb
            WHERE pdb.project.id = :projectId
            """)
    List<ProjectDesktopBannerDto> findByProjectId(@Param("projectId") int projectId);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectDesktopBannerDto(
            b.id, p.id, p.projectName, p.slugURL, b.desktopImage, b.desktopAltTag)
            FROM ProjectDesktopBanner b JOIN b.project p
            """)
    List<ProjectDesktopBannerDto> findAllBannerDtos();
}
