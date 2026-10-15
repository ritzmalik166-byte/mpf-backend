package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.BuilderLogoDto;
import com.mypropertyfact.estate.entities.Builder;
import com.mypropertyfact.estate.projections.BuilderView;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BuilderRepository extends JpaRepository<Builder, Integer> {
    Builder findByBuilderName(String builderName);
    
    Optional<Builder> findByBuilderNameIgnoreCase(String builderName);

    @Query("SELECT b FROM Builder b WHERE b.slugUrl = :url")
    Optional<Builder> findBySlugUrl(String url);

    List<BuilderView> findAllProjectedBy(Sort sort);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.BuilderLogoDto(b.id, b.builderName, b.slugUrl, b.builderLogo)
            FROM Builder b
            WHERE b.builderLogo IS NOT NULL AND TRIM(b.builderLogo) <> ''
            ORDER BY b.builderName
            """)
    List<BuilderLogoDto> findAllWithLogo();
}
