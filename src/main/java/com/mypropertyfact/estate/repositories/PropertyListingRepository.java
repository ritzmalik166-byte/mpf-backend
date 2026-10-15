package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.dtos.PropertyListingImagesDto;
import com.mypropertyfact.estate.dtos.PropertyShortDetailsDto;
import com.mypropertyfact.estate.entities.PropertyListing;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.enums.ProjectApprovalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
public interface PropertyListingRepository extends JpaRepository<PropertyListing, Long> {

    // Find all listings by user
    List<PropertyListing> findByUser(User user);

    // Find all listings by user ID
    List<PropertyListing> findByUserId(Integer userId);

    // Find listing by ID and user (for ownership verification)
    Optional<PropertyListing> findByIdAndUserId(Long id, Integer userId);

    // Find listings by approval status
    List<PropertyListing> findByApprovalStatus(ProjectApprovalStatus status);

    // Find listings by user and approval status
    List<PropertyListing> findByUserIdAndApprovalStatus(Integer userId, ProjectApprovalStatus status);

    // Find approved listings
    List<PropertyListing> findByApprovalStatusOrderByCreatedAtDesc(ProjectApprovalStatus status);

    // Find listings by city
    List<PropertyListing> findByCityId(Integer cityId);

    // Find listings by builder
    List<PropertyListing> findByBuilderId(Integer builderId);

    // Find listings by listing type (Residential/Commercial)
    List<PropertyListing> findByListingType(String listingType);

    // Find listings by transaction type (Sale/Rent)
    List<PropertyListing> findByTransaction(String transaction);

    // Search listings by title
    // Note: Description search removed due to CLOB type incompatibility with
    // LOWER() function
    @Query("SELECT pl FROM PropertyListing pl WHERE " +
            "LOWER(pl.title) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<PropertyListing> searchByKeyword(@Param("keyword") String keyword);

    // Find listings with pagination
    Page<PropertyListing> findByApprovalStatus(ProjectApprovalStatus status, Pageable pageable);

    // Count listings by user
    long countByUserId(Integer userId);

    long countByUserIdAndApprovalStatus(Integer userId, ProjectApprovalStatus status);

    long countByUserIdAndCreatedAtAfter(Integer userId, LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT pl.city.id) FROM PropertyListing pl WHERE pl.user.id = :userId AND pl.city IS NOT NULL")
    long countDistinctCitiesByUserId(@Param("userId") Integer userId);

    @Query("SELECT COUNT(DISTINCT pl.builder.id) FROM PropertyListing pl WHERE pl.user.id = :userId AND pl.builder IS NOT NULL")
    long countDistinctBuildersByUserId(@Param("userId") Integer userId);

    @Query("SELECT COUNT(DISTINCT a.id) FROM PropertyListing pl JOIN pl.amenities a WHERE pl.user.id = :userId")
    long countDistinctAmenitiesByUserId(@Param("userId") Integer userId);

    @Query("SELECT COUNT(DISTINCT pl.subType) FROM PropertyListing pl WHERE pl.user.id = :userId AND pl.subType IS NOT NULL AND pl.subType <> ''")
    long countDistinctPropertyTypesByUserId(@Param("userId") Integer userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PropertyListing pl SET pl.approvedBy = null WHERE pl.approvedBy.id = :userId")
    void clearApprovedByUserId(@Param("userId") Integer userId);

    // Count listings by approval status
    long countByApprovalStatus(ProjectApprovalStatus status);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.PropertyShortDetailsDto(
                pl.id,
                pl.listingType,
                pl.transaction,
                pl.subType,
                pl.status,
                pl.projectName,
                pl.builderName,
                pl.address,
                pl.localityName,
                pl.city.name,
                pl.pincode,
                pl.carpetArea,
                pl.builtUpArea,
                pl.totalPrice,
                pl.pricePerSqft,
                pl.bathrooms,
                pl.bedrooms,
                pl.facing,
                pl.createdAt
            )
            FROM PropertyListing pl
            WHERE pl.approvalStatus = :status
            ORDER BY pl.createdAt DESC
            """)
    List<PropertyShortDetailsDto> getPropertyShortDetails(@Param("status") ProjectApprovalStatus status);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.PropertyShortDetailsDto(
                pl.id,
                pl.listingType,
                pl.transaction,
                pl.subType,
                pl.status,
                pl.projectName,
                pl.builderName,
                pl.address,
                pl.localityName,
                c.name,
                pl.pincode,
                pl.carpetArea,
                pl.builtUpArea,
                pl.totalPrice,
                pl.pricePerSqft,
                pl.bathrooms,
                pl.bedrooms,
                pl.facing,
                pl.createdAt
            )
            FROM PropertyListing pl
            JOIN pl.city c
            WHERE pl.approvalStatus = :approval
              AND (:city IS NULL OR LOWER(c.name) = :city OR LOWER(c.slugUrl) = :city)
              AND (:listingType IS NULL OR LOWER(pl.listingType) = :listingType)
              AND (:transaction IS NULL OR LOWER(pl.transaction) = :transaction)
              AND (:subType IS NULL OR LOWER(pl.subType) = :subType)
              AND (:listingStatus IS NULL OR LOWER(pl.status) = :listingStatus)
              AND (:bedrooms IS NULL OR pl.bedrooms = :bedrooms)
              AND (:minPrice IS NULL OR pl.totalPrice >= :minPrice)
              AND (:maxPrice IS NULL OR pl.totalPrice <= :maxPrice)
            ORDER BY pl.createdAt DESC
            """)
    List<PropertyShortDetailsDto> findApprovedShortDetails(
            @Param("approval") ProjectApprovalStatus approval,
            @Param("city") String city,
            @Param("listingType") String listingType,
            @Param("transaction") String transaction,
            @Param("subType") String subType,
            @Param("listingStatus") String listingStatus,
            @Param("bedrooms") Integer bedrooms,
            @Param("minPrice") Double minPrice,
            @Param("maxPrice") Double maxPrice,
            org.springframework.data.domain.Pageable pageable);

    /** Rows of [propertyListingId, imageUrl] in display order. */
    @Query("""
            SELECT pi.propertyListing.id, pi.imageUrl
            FROM PropertyListingImage pi
            WHERE pi.propertyListing.id IN :ids
            ORDER BY pi.propertyListing.id, pi.displayOrder ASC
            """)
    List<Object[]> findImageUrlsByListingIds(@Param("ids") java.util.Collection<Long> ids);

    @Query("""
            SELECT new com.mypropertyfact.estate.dtos.PropertyListingImagesDto(
                pi.id,
                pi.imageUrl,
                pi.imageName
            ) FROM PropertyListingImage pi WHERE pi.propertyListing.id = :propertyListingId
            ORDER BY pi.displayOrder ASC
            """)
    List<PropertyListingImagesDto> findByPropertyListingId(Long propertyListingId);

    @Query("""
            SELECT pl FROM PropertyListing pl
            LEFT JOIN FETCH pl.city c
            LEFT JOIN FETCH c.state
            WHERE pl.id IN :ids
            """)
    List<PropertyListing> findAllWithCityStateByIdIn(@Param("ids") List<Long> ids);

    @Query("""
            SELECT pl.user.id, pl.approvalStatus, COUNT(pl)
            FROM PropertyListing pl
            WHERE pl.user.id IN :userIds
            GROUP BY pl.user.id, pl.approvalStatus
            """)
    List<Object[]> countGroupedByUserIdAndStatus(@Param("userIds") List<Integer> userIds);

    @Query("""
            SELECT DISTINCT pl FROM PropertyListing pl
            LEFT JOIN FETCH pl.user
            LEFT JOIN FETCH pl.city
            LEFT JOIN FETCH pl.approvedBy
            WHERE pl.approvalStatus = :status
            """)
    List<PropertyListing> findByApprovalStatusWithUserAndCity(
            @Param("status") ProjectApprovalStatus status);

    @Query("""
            SELECT DISTINCT pl FROM PropertyListing pl
            LEFT JOIN FETCH pl.user
            LEFT JOIN FETCH pl.city
            LEFT JOIN FETCH pl.builder
            LEFT JOIN FETCH pl.approvedBy
            WHERE pl.createdAt >= :from AND pl.createdAt < :to
            ORDER BY pl.createdAt DESC
            """)
    List<PropertyListing> findListedBetween(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
            SELECT DISTINCT pl FROM PropertyListing pl
            LEFT JOIN FETCH pl.user
            LEFT JOIN FETCH pl.city
            LEFT JOIN FETCH pl.builder
            LEFT JOIN FETCH pl.approvedBy
            WHERE pl.id = :id
            """)
    Optional<PropertyListing> findWithListingDetailsById(@Param("id") Long id);

    @Query("""
            SELECT DISTINCT pl.builderName FROM PropertyListing pl
            WHERE pl.builderName IS NOT NULL AND pl.builderName <> ''
            ORDER BY pl.builderName
            """)
    List<String> findDistinctBuilderNames();

    @Query("""
            SELECT DISTINCT c.name FROM PropertyListing pl
            JOIN pl.city c
            WHERE c.name IS NOT NULL AND c.name <> ''
            ORDER BY c.name
            """)
    List<String> findDistinctCityNames();
}
