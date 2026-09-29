package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.LocationBenefitDto;
import com.mypropertyfact.estate.entities.LocationBenefit;
import com.mypropertyfact.estate.entities.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface LocationBenefitRepository extends JpaRepository<LocationBenefit, Integer> {

    @Query("SELECT lb.id as id, lb.distance as distance, lb.benefitName as benefitName, lb.iconImage as image FROM " +
            "LocationBenefit lb")
    List<Object[]> getAllWithProjectName();

    List<LocationBenefit> findByProject(Project project);

    void deleteByProject(Project project);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.LocationBenefitDto(
            lb.benefitName,
            lb.distance
            )
            FROM LocationBenefit lb
            WHERE lb.project.id= :projectId
            """)
    List<LocationBenefitDto> findByProjectId(@Param("projectId") int projectId);

    @Query("""
            SELECT lb.project.id, lb.benefitName, lb.distance
            FROM LocationBenefit lb
            WHERE lb.project.id IN :ids
              AND lb.benefitName IS NOT NULL
            ORDER BY lb.id ASC
            """)
    List<Object[]> findCardBenefitsByProjectIds(@Param("ids") Collection<Integer> ids);
}
