package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.CityDetailDto;
import com.mypropertyfact.estate.dtos.CityNavDto;
import com.mypropertyfact.estate.entities.City;
import com.mypropertyfact.estate.projections.CityView;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CityRepository extends JpaRepository<City, Integer> {
    City findByName(String name);
    
    Optional<City> findByNameIgnoreCase(String name);

    Optional<City> findBySlugUrl(String url);

    List<CityView> findAllProjectedBy(Sort sort);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.CityDetailDto(
                c.id,
                s.id,
                s.country.id,
                0,
                c.metaTitle,
                c.metaKeyWords,
                c.metaDescription,
                c.name,
                s.stateName,
                s.country.countryName,
                c.cityDisc,
                c.cityImage,
                c.monumentName,
                c.monumentImage,
                c.cityHighlights,
                c.isActive,
                c.slugUrl,
                NULL,
                NULL
            )
            FROM City c
            LEFT JOIN c.state s
            ORDER BY c.name
            """)
    List<CityDetailDto> findAllCities();

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.CityNavDto(
                c.id,
                c.name,
                c.slugUrl,
                c.isActive,
                s.stateName
            )
            FROM City c
            LEFT JOIN c.state s
            ORDER BY c.name
            """)
    List<CityNavDto> findAllCityNav();

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.CityDetailDto(
                c.id,
                s.id,
                s.country.id,
                0,
                c.metaTitle,
                c.metaKeyWords,
                c.metaDescription,
                c.name,
                s.stateName,
                s.country.countryName,
                c.cityDisc,
                c.cityImage,
                c.monumentName,
                c.monumentImage,
                c.cityHighlights,
                c.isActive,
                c.slugUrl,
                NULL,
                NULL
            )
            FROM City c
            LEFT JOIN c.state s
            WHERE c.slugUrl = :slug
            """)
    CityDetailDto findCityDetails(@Param("slug") String slug);

}
