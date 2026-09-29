package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.ProjectGalleryShortDetails;
import com.mypropertyfact.estate.entities.ProjectGallery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProjectGalleryRepository extends JpaRepository<ProjectGallery, Integer> {
    List<ProjectGallery> findBySlugUrl(String url);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectGalleryShortDetails(
            pg.id,
            pg.image,
            pg.altTag
            )
            FROM ProjectGallery pg
            WHERE pg.project.id = :projectId
            """)
    List<ProjectGalleryShortDetails> findByProjectId(@Param("projectId") int projectId);

    @Query("""
            SELECT pg.project.id, pg.image
            FROM ProjectGallery pg
            WHERE pg.project.id IN :ids
              AND pg.image IS NOT NULL
            ORDER BY pg.id ASC
            """)
    List<Object[]> findImageNamesByProjectIds(@Param("ids") Collection<Integer> ids);
}
