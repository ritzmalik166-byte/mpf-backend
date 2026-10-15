package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.LocalityShortDto;
import com.mypropertyfact.estate.entities.Locality;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LocalityRepository extends JpaRepository<Locality, Long> {

    @Query("SELECT l FROM Locality l JOIN FETCH l.city")
    List<Locality> findAllWithCity();

    @Query("SELECT l FROM Locality l WHERE LOWER(l.localityName) = LOWER(:localityName) " +
           "AND l.city.id = :cityId AND (l.zone.id = :zoneId OR (l.zone IS NULL AND :zoneId IS NULL))")
    Optional<Locality> findByLocalityNameAndCityIdAndZoneId(
            @Param("localityName") String localityName,
            @Param("cityId") Integer cityId,
            @Param("zoneId") Long zoneId);

    Optional<Locality> findBySlug(String slug);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.LocalityShortDto(
            l.id,
            l.localityName
            )
            FROM Locality l WHERE
            l.city.id = :cityId
            """)
    List<LocalityShortDto> findAllLocalitiesOfCity(@Param("cityId") int cityId);

    /** Rows of [cityId, localityId, localityName] for every locality, in one query. */
    @Query("SELECT l.city.id, l.id, l.localityName FROM Locality l")
    List<Object[]> findAllLocalityRowsWithCityId();
}
