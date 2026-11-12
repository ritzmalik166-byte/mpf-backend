package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.entities.ProjectBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectBannerRepository extends JpaRepository<ProjectBanner, Integer> {
    @Query("SELECT p FROM ProjectBanner p WHERE slugURL= :slugURL")
    ProjectBanner getBySlug(@Param("slugURL") String slugURL);

    /** Legacy banner rows as [id, projectId, desktopBanner, mobileBanner, altTag], without loading Project entities. */
    @Query("SELECT b.id, p.id, b.desktopBanner, b.mobileBanner, b.altTag FROM ProjectBanner b JOIN b.project p")
    java.util.List<Object[]> findAllLite();
}
