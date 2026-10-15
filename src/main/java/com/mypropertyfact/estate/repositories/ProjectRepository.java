package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.ProjectExportDto;
import com.mypropertyfact.estate.dtos.ProjectFullDetails;
import com.mypropertyfact.estate.dtos.ProjectShortDetails;
import com.mypropertyfact.estate.entities.Project;
import com.mypropertyfact.estate.models.ProjectAmenityResponse;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Integer> {

    /** Rows of [projectId, projectName]. */
    @Query("SELECT p.id, p.projectName FROM Project p WHERE p.id IN :ids")
    List<Object[]> findNamesByIds(@Param("ids") java.util.Collection<Integer> ids);


    Optional<Project> findBySlugURL(String url);

    Optional<Project> findFirstByProjectNameIgnoreCase(String projectName);

    @EntityGraph(value = "Project.withAllRelations", type = EntityGraph.EntityGraphType.LOAD)
    @Query("SELECT p FROM Project p WHERE p.slugURL = :url AND p.status = true")
    Optional<Project> findBySlugURLWithAllRelations(@Param("url") String url);

    @EntityGraph(value = "Project.withAllRelations", type = EntityGraph.EntityGraphType.LOAD)
    @Query("SELECT p FROM Project p WHERE p.slugURL = :url")
    Optional<Project> findBySlugURLWithAllRelationsNoFilter(@Param("url") String url);

    @Query("""
            SELECT p FROM Project p
            WHERE p.status = true
            AND (:type IS NULL OR p.projectTypes.id = :type)
            AND (:city IS NULL OR p.city.id = :city)
            AND (CAST(p.projectPrice AS float) BETWEEN :start AND :end)
            """)
    List<Project> searchProjects(
            @Param("type") Integer type,
            @Param("city") Integer city,
            @Param("start") float start,
            @Param("end") float end
    );

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectShortDetails(
                p.id,
                p.projectName,
                p.projectPrice,
                p.slugURL,
                p.projectLocality,
                p.projectConfiguration,
                p.status,
                b.builderName,
                ps.statusName,
                pt.projectTypeName,
                c.name,
                CONCAT(p.projectLocality, ', ', c.name),
                p.projectThumbnail,
                p.projectLogo,
                pdb.desktopImage,
                b.slugUrl,
                c.slugUrl,
                p.createdAt,
                p.updatedAt
            )
            FROM Project p
            LEFT JOIN p.projectStatus ps
            LEFT JOIN p.city c
            LEFT JOIN p.builder b
            LEFT JOIN p.projectDesktopBanners pdb
            LEFT JOIN p.projectTypes pt
            WHERE
                (pdb.id IS NULL OR pdb.id = (
                    SELECT MIN(pdb2.id)
                    FROM ProjectDesktopBanner pdb2
                    WHERE pdb2.project = p
                ))
            ORDER BY p.projectName
            """)
    List<ProjectShortDetails> findAllProjects();

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectExportDto(
                p.id,
                p.projectName,
                p.slugURL,
                b.id,
                b.builderName,
                c.id,
                c.name,
                COALESCE(s.stateName, pst.stateName),
                COALESCE(co.countryName, pco.countryName),
                COALESCE(co.id, pco.id),
                COALESCE(s.id, pst.id),
                pt.id,
                pt.projectTypeName,
                ps.id,
                ps.statusName,
                p.status,
                p.showFeaturedProperties,
                p.projectLocality,
                p.projectConfiguration,
                p.projectPrice,
                p.ivrNo,
                p.reraNo,
                p.reraQr,
                p.reraWebsite,
                p.locationMap,
                p.projectLogo,
                p.projectThumbnail,
                p.projectThumbnailAltTag,
                p.projectLogoAltTag,
                p.locationMapAltTag,
                p.metaTitle,
                p.metaKeyword,
                p.metaDescription,
                p.amenityDesc,
                p.floorPlanDesc,
                p.locationDesc,
                p.createdAt,
                p.updatedAt,
                pdb.desktopImage
            )
            FROM Project p
            LEFT JOIN p.builder b
            LEFT JOIN p.city c
            LEFT JOIN c.state s
            LEFT JOIN s.country co
            LEFT JOIN p.state pst
            LEFT JOIN pst.country pco
            LEFT JOIN p.projectTypes pt
            LEFT JOIN p.projectStatus ps
            LEFT JOIN p.projectDesktopBanners pdb
            WHERE (pdb.id IS NULL OR pdb.id = (
                SELECT MIN(pdb2.id)
                FROM ProjectDesktopBanner pdb2
                WHERE pdb2.project = p
            ))
            ORDER BY p.id
            """)
    List<ProjectExportDto> findAllForExcelExport();

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectFullDetails(
                p.id,
                p.metaTitle,
                p.metaKeyword,
                p.metaDescription,
                p.projectName,
                p.projectLocality,
                p.projectConfiguration,
                p.projectPrice,
                p.ivrNo,
                p.locationMap,
                p.reraNo,
                p.reraQr,
                p.reraWebsite,
                p.projectLogo,
                p.slugURL,
                p.showFeaturedProperties,
                p.projectLogoAltTag,
                p.locationMapAltTag,
                p.status,
                p.amenityDesc,
                p.floorPlanDesc,
                p.locationDesc,
                new com.mypropertyfact.estate.dtos.BuilderDto(
                    b.id,
                    b.builderName,
                    b.builderDesc,
                    b.slugUrl
                ),
                p.createdAt,
                p.updatedAt,
                w.walkthroughDesc,
                COALESCE(s.stateName, pst.stateName),
                c.name,
                COALESCE(co.countryName, pco.countryName),
                pt.projectTypeName,
                COALESCE(co.id, pco.id),
                COALESCE(s.id, pst.id),
                c.id,
                p.projectThumbnail,
                pt.id,
                ps.id
            )
            FROM Project p
            LEFT JOIN p.builder b
            LEFT JOIN p.projectWalkthroughs w
            LEFT JOIN p.city c
            LEFT JOIN c.state s
            LEFT JOIN s.country co
            LEFT JOIN p.state pst
            LEFT JOIN pst.country pco
            LEFT JOIN p.projectTypes pt
            LEFT JOIN p.projectStatus ps
            WHERE p.slugURL = :slug
            AND p.status = true
            AND (w.id IS NULL OR w.id = (
                SELECT MAX(w2.id) FROM ProjectWalkthrough w2 WHERE w2.project = p
            ))
        """)
    Optional<ProjectFullDetails> findProjectFullDetails(@Param("slug") String slug);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectFullDetails(
                p.id,
                p.metaTitle,
                p.metaKeyword,
                p.metaDescription,
                p.projectName,
                p.projectLocality,
                p.projectConfiguration,
                p.projectPrice,
                p.ivrNo,
                p.locationMap,
                p.reraNo,
                p.reraQr,
                p.reraWebsite,
                p.projectLogo,
                p.slugURL,
                p.showFeaturedProperties,
                p.projectLogoAltTag,
                p.locationMapAltTag,
                p.status,
                p.amenityDesc,
                p.floorPlanDesc,
                p.locationDesc,
                new com.mypropertyfact.estate.dtos.BuilderDto(
                    b.id,
                    b.builderName,
                    b.builderDesc,
                    b.slugUrl
                ),
                p.createdAt,
                p.updatedAt,
                w.walkthroughDesc,
                COALESCE(s.stateName, pst.stateName),
                c.name,
                COALESCE(co.countryName, pco.countryName),
                pt.projectTypeName,
                COALESCE(co.id, pco.id),
                COALESCE(s.id, pst.id),
                c.id,
                p.projectThumbnail,
                pt.id,
                ps.id
            )
            FROM Project p
            LEFT JOIN p.builder b
            LEFT JOIN p.projectWalkthroughs w
            LEFT JOIN p.city c
            LEFT JOIN c.state s
            LEFT JOIN s.country co
            LEFT JOIN p.state pst
            LEFT JOIN pst.country pco
            LEFT JOIN p.projectTypes pt
            LEFT JOIN p.projectStatus ps
            WHERE p.slugURL = :slug
            AND (w.id IS NULL OR w.id = (
                SELECT MAX(w2.id) FROM ProjectWalkthrough w2 WHERE w2.project = p
            ))
        """)
    Optional<ProjectFullDetails> findProjectFullDetailsForAdmin(@Param("slug") String slug);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.ProjectFullDetails(
                p.id,
                p.metaTitle,
                p.metaKeyword,
                p.metaDescription,
                p.projectName,
                p.projectLocality,
                p.projectConfiguration,
                p.projectPrice,
                p.ivrNo,
                p.locationMap,
                p.reraNo,
                p.reraQr,
                p.reraWebsite,
                p.projectLogo,
                p.slugURL,
                p.showFeaturedProperties,
                p.projectLogoAltTag,
                p.locationMapAltTag,
                p.status,
                p.amenityDesc,
                p.floorPlanDesc,
                p.locationDesc,
                new com.mypropertyfact.estate.dtos.BuilderDto(
                    b.id,
                    b.builderName,
                    b.builderDesc,
                    b.slugUrl
                ),
                p.createdAt,
                p.updatedAt,
                w.walkthroughDesc,
                COALESCE(s.stateName, pst.stateName),
                c.name,
                COALESCE(co.countryName, pco.countryName),
                pt.projectTypeName,
                COALESCE(co.id, pco.id),
                COALESCE(s.id, pst.id),
                c.id,
                p.projectThumbnail,
                pt.id,
                ps.id
            )
            FROM Project p
            LEFT JOIN p.builder b
            LEFT JOIN p.projectWalkthroughs w
            LEFT JOIN p.city c
            LEFT JOIN c.state s
            LEFT JOIN s.country co
            LEFT JOIN p.state pst
            LEFT JOIN pst.country pco
            LEFT JOIN p.projectTypes pt
            LEFT JOIN p.projectStatus ps
            WHERE p.id = :id
            AND (w.id IS NULL OR w.id = (
                SELECT MAX(w2.id) FROM ProjectWalkthrough w2 WHERE w2.project = p
            ))
        """)
    Optional<ProjectFullDetails> findProjectFullDetailsByIdForAdmin(@Param("id") int id);

    @Query("""
            SELECT p FROM Project p
            LEFT JOIN FETCH p.city c
            LEFT JOIN FETCH c.state
            LEFT JOIN FETCH p.state
            WHERE p.id IN :ids
            """)
    List<Project> findAllWithCityStateByIdIn(@Param("ids") List<Integer> ids);

    @Query("""
            SELECT p FROM Project p
            LEFT JOIN FETCH p.city c
            LEFT JOIN FETCH c.state
            LEFT JOIN FETCH p.state
            WHERE p.slugURL IN :slugs
            """)
    List<Project> findAllWithCityStateBySlugIn(@Param("slugs") List<String> slugs);

    @Query("""
            SELECT new com.mypropertyfact.estate.models.ProjectAmenityResponse(
                p.id,
                p.projectName,
                ''
            )
            FROM Project p
            WHERE SIZE(p.amenities) > 0
            ORDER BY p.projectName
            """)
    List<ProjectAmenityResponse> findProjectsWithAmenities();

    @Query("""
            SELECT DISTINCT p FROM Project p
            LEFT JOIN FETCH p.city
            LEFT JOIN FETCH p.builder
            LEFT JOIN FETCH p.projectTypes
            LEFT JOIN FETCH p.projectStatus
            LEFT JOIN FETCH p.createdBy
            WHERE p.status = true
            """)
    List<Project> findLiveWithDetails();

    @Query("""
            SELECT DISTINCT p FROM Project p
            LEFT JOIN FETCH p.city
            LEFT JOIN FETCH p.builder
            LEFT JOIN FETCH p.projectTypes
            LEFT JOIN FETCH p.projectStatus
            LEFT JOIN FETCH p.createdBy
            WHERE p.createdAt >= :from AND p.createdAt < :to
            ORDER BY p.createdAt DESC
            """)
    List<Project> findListedBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
            SELECT DISTINCT p FROM Project p
            LEFT JOIN FETCH p.city
            LEFT JOIN FETCH p.builder
            LEFT JOIN FETCH p.projectTypes
            LEFT JOIN FETCH p.projectStatus
            LEFT JOIN FETCH p.createdBy
            WHERE p.id = :id
            """)
    Optional<Project> findWithListingDetailsById(@Param("id") int id);

    @Query("""
            SELECT DISTINCT b.builderName FROM Project p
            JOIN p.builder b
            WHERE b.builderName IS NOT NULL AND b.builderName <> ''
            ORDER BY b.builderName
            """)
    List<String> findDistinctBuilderNames();

    @Query("""
            SELECT DISTINCT c.name FROM Project p
            JOIN p.city c
            WHERE c.name IS NOT NULL AND c.name <> ''
            ORDER BY c.name
            """)
    List<String> findDistinctCityNames();

}
