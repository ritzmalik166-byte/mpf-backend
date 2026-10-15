package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.dtos.NearbyBenefitDto;
import com.mypropertyfact.estate.dtos.NearbyBenefitResponseDto;
import com.mypropertyfact.estate.dtos.PropertyListingDto;
import com.mypropertyfact.estate.dtos.PropertyListingImagesDto;
import com.mypropertyfact.estate.dtos.PropertyListingRequestDto;
import com.mypropertyfact.estate.dtos.PropertyShortDetailsDto;
import com.mypropertyfact.estate.entities.*;
import com.mypropertyfact.estate.enums.ProjectApprovalStatus;
import com.mypropertyfact.estate.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyListingService {

    private final PropertyListingRepository propertyListingRepository;

    private final UserRoleService userRoleService;

    private final PropertyListingImageRepository propertyListingImageRepository;

    private final CityRepository cityRepository;

    private final BuilderRepository builderRepository;

    private final LocalityRepository localityRepository;

    private final ProjectTypeRepository projectTypeRepository;

    private final ProjectStatusRepository projectStatusRepository;

    private final AmenityRepository amenityRepository;

    private final FeatureRepository featureRepository;

    private final MasterBenefitRepository masterBenefitRepository;

    private final PropertyListingNearbyBenefitRepository propertyListingNearbyBenefitRepository;

    private final AdminDashboardActivityService adminDashboardActivityService;

    private final ListingActivityService listingActivityService;

    @Value("${upload_dir:uploads/}")
    private String uploadDir;

    /**
     * Create a new property listing from form data and images
     * Transaction will rollback on any exception
     */
    @Transactional(rollbackFor = Exception.class)
    public PropertyListing createPropertyListing(PropertyListingRequestDto dto, MultipartFile[] images, User user) {
        log.info("Creating property listing for user: {}", user.getEmail());

        PropertyListing listing = new PropertyListing();

        // ========== BASIC INFORMATION ==========
        listing.setListingType(dto.getListingType());
        listing.setTransaction(dto.getTransaction());
        listing.setSubType(dto.getSubType());
        listing.setTitle(dto.getTitle() != null ? dto.getTitle() : generateTitle(dto));
        listing.setDescription(dto.getDescription());
        listing.setStatus(dto.getStatus());
        listing.setPossession(dto.getPossession());
        listing.setOccupancy(dto.getOccupancy());
        listing.setNoticePeriod(dto.getNoticePeriod());

        // ========== LOCATION & AREA ==========
        listing.setProjectName(dto.getProjectName());
        listing.setBuilderName(dto.getBuilderName());
        listing.setAddress(dto.getAddress());
        listing.setLocalityName(dto.getLocality());
        listing.setPincode(dto.getPinCode());
        listing.setLatitude(dto.getLatitude());
        listing.setLongitude(dto.getLongitude());

        // Area Details
        listing.setCarpetArea(dto.getCarpetArea());
        listing.setBuiltUpArea(dto.getBuiltUpArea());
        listing.setSuperBuiltUpArea(dto.getSuperBuiltUpArea());
        listing.setPlotArea(dto.getPlotArea());

        // ========== PRICING ==========
        listing.setTotalPrice(dto.getTotalPrice());
        listing.setPricePerSqft(dto.getPricePerSqft());
        listing.setMaintenanceCharges(dto.getMaintenanceCam()); // Map maintenanceCam to maintenanceCharges
        listing.setBookingAmount(dto.getBookingAmount());

        // ========== PROPERTY DETAILS ==========
        listing.setFloorNumber(dto.getFloorNo());
        listing.setTotalFloors(dto.getTotalFloors());
        listing.setFacing(dto.getFacing());
        listing.setAgeOfConstruction(dto.getAgeOfConstruction());

        // ========== CONFIGURATION ==========
        listing.setBedrooms(dto.getBedrooms());
        listing.setBathrooms(dto.getBathrooms());
        listing.setBalconies(dto.getBalconies());
        listing.setParking(dto.getParkingType());
        listing.setFurnished(dto.getFurnishingLevel());

        // Set Features by IDs
        if (dto.getFeatureIds() != null && !dto.getFeatureIds().isEmpty()) {
            Set<Feature> featureSet = dto.getFeatureIds().stream()
                    .map(featureRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toSet());
            listing.setFeatures(featureSet);
        } else if (dto.getIncludedItems() != null && !dto.getIncludedItems().isEmpty()) {
            // Fallback: Handle feature names if IDs not provided (for backward
            // compatibility)
            Set<Feature> featureSet = new HashSet<>();
            for (String featureName : dto.getIncludedItems()) {
                Feature feature = featureRepository.findByTitleIgnoreCase(featureName)
                        .orElseGet(() -> {
                            log.info("Creating new feature: {}", featureName);
                            Feature newFeature = new Feature();
                            newFeature.setTitle(featureName);
                            newFeature.setAltTag(featureName.toLowerCase().replace(" ", "-"));
                            newFeature.setStatus(true);
                            return featureRepository.save(newFeature);
                        });
                featureSet.add(feature);
            }
            listing.setFeatures(featureSet);
        }

        // ========== MEDIA & CONTACT ==========
        listing.setVirtualTour(dto.getVideoUrl());
        listing.setOwnershipType(dto.getOwnershipType());
        listing.setReraId(dto.getReraId());
        listing.setReraState(dto.getReraState());
        // Contact information - use contactName/contactPhone/contactEmail, fallback to
        // primaryContact/primaryEmail
        listing.setContactName(dto.getContactName());
        listing.setContactPhone(dto.getContactPhone() != null ? dto.getContactPhone()
                : (dto.getPrimaryContact() != null ? dto.getPrimaryContact() : null));
        listing.setContactEmail(dto.getContactEmail() != null ? dto.getContactEmail()
                : (dto.getPrimaryEmail() != null ? dto.getPrimaryEmail() : null));
        listing.setContactPreference(dto.getContactPreference() != null ? dto.getContactPreference() : "Phone");
        listing.setPreferredTime(dto.getPreferredTime());
        listing.setAdditionalNotes(dto.getAdditionalNotes());
        listing.setTruthfulDeclaration(dto.getTruthfulDeclaration() != null ? dto.getTruthfulDeclaration() : true);
        listing.setDpdpConsent(dto.getDpdpConsent() != null ? dto.getDpdpConsent() : true);

        // ========== RELATIONSHIPS ==========
        listing.setUser(user);

        // Set City
        if (dto.getCityId() != null) {
            cityRepository.findById(dto.getCityId()).ifPresent(listing::setCity);
        } else if (dto.getCity() != null) {
            City city = cityRepository.findByNameIgnoreCase(dto.getCity())
                    .orElseGet(() -> {
                        log.info("Creating new city: {}", dto.getCity());
                        City newCity = new City();
                        newCity.setName(dto.getCity());
                        newCity.setSlugUrl(generateSlug(dto.getCity()));
                        newCity.setCreatedAt(LocalDateTime.now());
                        newCity.setUpdatedAt(LocalDateTime.now());
                        return cityRepository.save(newCity);
                    });
            listing.setCity(city);
        }

        // Set Builder
        if (dto.getBuilderId() != null) {
            builderRepository.findById(dto.getBuilderId()).ifPresent(listing::setBuilder);
        } else if (dto.getBuilderName() != null && !dto.getBuilderName().isEmpty()) {
            Builder builder = builderRepository.findByBuilderNameIgnoreCase(dto.getBuilderName())
                    .orElseGet(() -> {
                        log.info("Creating new builder: {}", dto.getBuilderName());
                        Builder newBuilder = new Builder();
                        newBuilder.setBuilderName(dto.getBuilderName());
                        newBuilder.setSlugUrl(generateSlug(dto.getBuilderName()));
                        newBuilder.setCreatedAt(LocalDateTime.now());
                        newBuilder.setUpdatedAt(LocalDateTime.now());
                        return builderRepository.save(newBuilder);
                    });
            listing.setBuilder(builder);
        }

        // Set Locality
        if (dto.getLocalityId() != null) {
            localityRepository.findById((long) dto.getLocalityId()).ifPresent(listing::setLocality);
        }

        // Set Listing Type Entity (ProjectTypes)
        if (dto.getListingType() != null) {
            projectTypeRepository.findByProjectTypeNameIgnoreCase(dto.getListingType())
                    .ifPresent(listing::setListingTypeEntity);
        }

        // Set Status Entity (ProjectStatus) - try to find by status name
        if (dto.getStatus() != null) {
            projectStatusRepository.findByStatusNameIgnoreCase(dto.getStatus())
                    .ifPresent(listing::setStatusEntity);
            // If not found by name, status will remain as string in listing.status field
        }

        // Set Amenities
        if (dto.getAmenityIds() != null && !dto.getAmenityIds().isEmpty()) {
            Set<Amenity> amenitySet = dto.getAmenityIds().stream()
                    .map(amenityRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toSet());
            listing.setAmenities(amenitySet);
        } else if (dto.getSocietyFeatures() != null && !dto.getSocietyFeatures().isEmpty()) {
            // Handle amenity names if IDs not provided
            Set<Amenity> amenitySet = new HashSet<>();
            for (String amenityName : dto.getSocietyFeatures()) {
                Amenity amenity = amenityRepository.findByTitleIgnoreCase(amenityName)
                        .orElseGet(() -> {
                            log.info("Creating new amenity: {}", amenityName);
                            Amenity newAmenity = new Amenity();
                            newAmenity.setTitle(amenityName);
                            newAmenity.setAltTag(amenityName.toLowerCase().replace(" ", "-"));
                            newAmenity.setStatus(true);
                            return amenityRepository.save(newAmenity);
                        });
                amenitySet.add(amenity);
            }
            listing.setAmenities(amenitySet);
        }

        // Set approval status - use from DTO if provided, otherwise default to PENDING
        if (dto.getApprovalStatus() != null && !dto.getApprovalStatus().isEmpty()) {
            try {
                listing.setApprovalStatus(ProjectApprovalStatus.valueOf(dto.getApprovalStatus().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid approval status '{}', defaulting to PENDING", dto.getApprovalStatus());
                listing.setApprovalStatus(ProjectApprovalStatus.PENDING);
            }
        } else {
            listing.setApprovalStatus(ProjectApprovalStatus.PENDING);
        }

        // Set isUserSubmitted - use from DTO if provided, otherwise default based on
        // approval status
        if (dto.getIsUserSubmitted() != null) {
            listing.setIsUserSubmitted(dto.getIsUserSubmitted());
        } else {
            // Default: DRAFT = false (not submitted), others = true (submitted)
            listing.setIsUserSubmitted(listing.getApprovalStatus() != ProjectApprovalStatus.DRAFT);
        }

        try {
            // Save the listing first to get the ID
            PropertyListing savedListing = propertyListingRepository.save(listing);

            // ========== HANDLE NEARBY BENEFITS WITH DISTANCES ==========
            if (dto.getNearbyBenefits() != null && !dto.getNearbyBenefits().isEmpty()) {
                List<PropertyListingNearbyBenefit> nearbyBenefitList = new ArrayList<>();
                for (NearbyBenefitDto benefitDto : dto.getNearbyBenefits()) {
                    if (benefitDto.getId() != null && benefitDto.getDistance() != null
                            && benefitDto.getDistance() > 0) {
                        MasterBenefit masterBenefit = masterBenefitRepository.findById(benefitDto.getId())
                                .orElse(null);
                        if (masterBenefit != null) {
                            PropertyListingNearbyBenefit nearbyBenefit = new PropertyListingNearbyBenefit();
                            nearbyBenefit.setPropertyListing(savedListing);
                            nearbyBenefit.setMasterBenefit(masterBenefit);
                            nearbyBenefit.setDistance(benefitDto.getDistance());
                            nearbyBenefitList.add(nearbyBenefit);
                        }
                    }
                }
                if (!nearbyBenefitList.isEmpty()) {
                    propertyListingNearbyBenefitRepository.saveAll(nearbyBenefitList);
                    log.info("Saved {} nearby benefits for property listing {}", nearbyBenefitList.size(),
                            savedListing.getId());
                }
            }

            // ========== HANDLE IMAGES ==========
            if (images != null && images.length > 0) {
                savePropertyImages(savedListing, images);
            }

            log.info("Property listing created successfully with ID: {}", savedListing.getId());
            listingActivityService.record(
                    ListingActivityService.SOURCE_PORTAL,
                    savedListing.getId(),
                    ListingActivityService.ACTION_CREATED,
                    user,
                    listingTitle(savedListing));
            return savedListing;
        } catch (Exception e) {
            log.error("Error creating property listing: {}", e.getMessage(), e);
            // Exception will trigger transaction rollback due to @Transactional(rollbackFor
            // = Exception.class)
            throw new RuntimeException("Failed to create property listing: " + e.getMessage(), e);
        }
    }

    /**
     * Save images for a property listing
     * Note: This method should be called within an existing transaction
     * to ensure rollback if image saving fails
     */
    public void savePropertyImages(PropertyListing listing, MultipartFile[] images) {
        String listingDir = null;
        Path listingPath = null;
        List<Path> savedFiles = new ArrayList<>();

        try {
            // Create directory for property listing images
            listingDir = uploadDir + "property-listings" + File.separator + listing.getId() + File.separator;
            listingPath = Paths.get(listingDir);
            Files.createDirectories(listingPath);

            for (int i = 0; i < images.length; i++) {
                MultipartFile imageFile = images[i];
                if (imageFile == null || imageFile.isEmpty()) {
                    continue;
                }

                // Generate unique filename
                String originalFilename = imageFile.getOriginalFilename();
                String fileExtension = "";
                if (originalFilename != null && originalFilename.contains(".")) {
                    fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
                }
                String fileName = System.currentTimeMillis() + "_" + i + fileExtension;

                // Save file
                Path filePath = listingPath.resolve(fileName);
                Files.copy(imageFile.getInputStream(), filePath);
                savedFiles.add(filePath);

                // Create image entity
                PropertyListingImage image = new PropertyListingImage();
                // Store relative path for easier URL construction
                // Format: property-listings/{id}/{filename}
                String relativePath = "property-listings" + File.separator + listing.getId() + File.separator
                        + fileName;
                image.setImageUrl(relativePath);
                image.setImageName(originalFilename);
                image.setImageSize(imageFile.getSize());
                image.setMimeType(imageFile.getContentType());
                image.setDisplayOrder(i);
                image.setIsPrimary(i == 0); // First image is primary
                image.setPropertyListing(listing);

                propertyListingImageRepository.save(image);

                log.info("Saved image {} for listing {}", fileName, listing.getId());
            }
        } catch (IOException e) {
            log.error("Error saving images for listing {}: {}", listing.getId(), e.getMessage(), e);
            // Cleanup: Delete any files that were saved before the error
            cleanupSavedFiles(savedFiles);
            throw new RuntimeException("Failed to save images: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error saving images for listing {}: {}", listing.getId(), e.getMessage(), e);
            // Cleanup: Delete any files that were saved before the error
            cleanupSavedFiles(savedFiles);
            throw new RuntimeException("Failed to save images: " + e.getMessage(), e);
        }
    }

    /**
     * Cleanup saved files if transaction fails
     */
    private void cleanupSavedFiles(List<Path> savedFiles) {
        for (Path filePath : savedFiles) {
            try {
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                    log.info("Cleaned up file: {}", filePath);
                }
            } catch (IOException e) {
                log.warn("Failed to cleanup file {}: {}", filePath, e.getMessage());
            }
        }
    }

    /**
     * Get property listing by ID
     */
    @Transactional(readOnly = true)
    public Optional<PropertyListingDto> getPropertyListingById(Long id) {
        return propertyListingRepository.findById(id)
                .map(this::convertToDto);
    }

    /**
     * Get all property listings for a user
     */
    @Transactional(readOnly = true)
    public List<PropertyListingDto> getUserPropertyListings(Integer userId) {
        List<PropertyListing> listings = propertyListingRepository.findByUserId(userId);
        return listings.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Get property listings by approval status
     */
    @Transactional(readOnly = true)
    public List<PropertyListingDto> getPropertyListingsByStatus(ProjectApprovalStatus status) {
        List<PropertyListing> listings = propertyListingRepository.findByApprovalStatus(status);
        return listings.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Get all approved property listings with optional filters (public access)
     */
    @Transactional(readOnly = true)
    public List<PropertyShortDetailsDto> getApprovedPropertyListings(
            String city, String listingType, String transaction, Integer bedrooms,
            String status, Double minPrice, Double maxPrice, String subType) {
        return getApprovedPropertyListings(
                city, listingType, transaction, bedrooms, status, minPrice, maxPrice, subType, null);
    }

    @Transactional(readOnly = true)
    public List<PropertyShortDetailsDto> getApprovedPropertyListings(
            String city, String listingType, String transaction, Integer bedrooms,
            String status, Double minPrice, Double maxPrice, String subType, Integer limit) {

        Pageable pageable = limit != null && limit > 0
                ? PageRequest.of(0, Math.min(limit, 200))
                : Pageable.unpaged();
        List<PropertyShortDetailsDto> listings = propertyListingRepository.findApprovedShortDetails(
                ProjectApprovalStatus.APPROVED,
                lowerOrNull(city),
                lowerOrNull(listingType),
                lowerOrNull(transaction),
                lowerOrNull(subType),
                lowerOrNull(status),
                bedrooms != null && bedrooms > 0 ? bedrooms : null,
                minPrice,
                maxPrice,
                pageable);
        if (listings.isEmpty()) return listings;

        List<Long> ids = listings.stream().map(PropertyShortDetailsDto::getId).toList();
        Map<Long, List<String>> imagesByListing = new HashMap<>();
        for (Object[] row : propertyListingRepository.findImageUrlsByListingIds(ids)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            imagesByListing
                    .computeIfAbsent(((Number) row[0]).longValue(), ignored -> new ArrayList<>())
                    .add(String.valueOf(row[1]));
        }
        for (PropertyShortDetailsDto listing : listings) {
            listing.setImageUrls(imagesByListing.getOrDefault(listing.getId(), new ArrayList<>()));
        }
        return listings;
    }

    private static String lowerOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Update property listing
     */
    @Transactional
    public PropertyListing updatePropertyListing(Long id, PropertyListingRequestDto dto, MultipartFile[] images,
            User user) {
        // Check if user is admin - admins can update any listing
        boolean isAdmin = userRoleService.userHasRole(user.getId(), "SUPERADMIN");

        PropertyListing listing;
        if (isAdmin) {
            listing = propertyListingRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Property listing not found"));
        } else {
            listing = propertyListingRepository.findByIdAndUserId(id, user.getId())
                    .orElseThrow(() -> new RuntimeException("Property listing not found or unauthorized"));
        }
        ProjectApprovalStatus previousStatus = listing.getApprovalStatus();
        List<String> changes = portalChangeSummary(listing, dto, images);

        // ========== UPDATE BASIC INFORMATION ==========
        if (dto.getListingType() != null)
            listing.setListingType(dto.getListingType());
        if (dto.getTransaction() != null)
            listing.setTransaction(dto.getTransaction());
        if (dto.getSubType() != null)
            listing.setSubType(dto.getSubType());
        if (dto.getTitle() != null)
            listing.setTitle(dto.getTitle());
        else if (dto.getBedrooms() != null || dto.getSubType() != null || dto.getLocality() != null) {
            // Regenerate title if key fields changed
            listing.setTitle(generateTitle(dto));
        }
        if (dto.getDescription() != null)
            listing.setDescription(dto.getDescription());
        if (dto.getStatus() != null)
            listing.setStatus(dto.getStatus());
        if (dto.getPossession() != null)
            listing.setPossession(dto.getPossession());
        if (dto.getOccupancy() != null)
            listing.setOccupancy(dto.getOccupancy());
        if (dto.getNoticePeriod() != null)
            listing.setNoticePeriod(dto.getNoticePeriod());

        // ========== UPDATE LOCATION & AREA ==========
        if (dto.getProjectName() != null)
            listing.setProjectName(dto.getProjectName());
        if (dto.getBuilderName() != null)
            listing.setBuilderName(dto.getBuilderName());
        if (dto.getAddress() != null)
            listing.setAddress(dto.getAddress());
        if (dto.getLocality() != null)
            listing.setLocalityName(dto.getLocality());
        if (dto.getPinCode() != null)
            listing.setPincode(dto.getPinCode());
        if (dto.getLatitude() != null)
            listing.setLatitude(dto.getLatitude());
        if (dto.getLongitude() != null)
            listing.setLongitude(dto.getLongitude());

        // Area Details
        if (dto.getCarpetArea() != null)
            listing.setCarpetArea(dto.getCarpetArea());
        if (dto.getBuiltUpArea() != null)
            listing.setBuiltUpArea(dto.getBuiltUpArea());
        if (dto.getSuperBuiltUpArea() != null)
            listing.setSuperBuiltUpArea(dto.getSuperBuiltUpArea());
        if (dto.getPlotArea() != null)
            listing.setPlotArea(dto.getPlotArea());

        // ========== UPDATE PRICING ==========
        if (dto.getTotalPrice() != null)
            listing.setTotalPrice(dto.getTotalPrice());
        if (dto.getPricePerSqft() != null)
            listing.setPricePerSqft(dto.getPricePerSqft());
        if (dto.getMaintenanceCam() != null)
            listing.setMaintenanceCharges(dto.getMaintenanceCam());
        if (dto.getBookingAmount() != null)
            listing.setBookingAmount(dto.getBookingAmount());

        // ========== UPDATE PROPERTY DETAILS ==========
        if (dto.getFloorNo() != null)
            listing.setFloorNumber(dto.getFloorNo());
        if (dto.getTotalFloors() != null)
            listing.setTotalFloors(dto.getTotalFloors());
        if (dto.getFacing() != null)
            listing.setFacing(dto.getFacing());
        if (dto.getAgeOfConstruction() != null)
            listing.setAgeOfConstruction(dto.getAgeOfConstruction());

        // ========== UPDATE CONFIGURATION ==========
        if (dto.getBedrooms() != null)
            listing.setBedrooms(dto.getBedrooms());
        if (dto.getBathrooms() != null)
            listing.setBathrooms(dto.getBathrooms());
        if (dto.getBalconies() != null)
            listing.setBalconies(dto.getBalconies());
        if (dto.getParkingType() != null)
            listing.setParking(dto.getParkingType());
        if (dto.getFurnishingLevel() != null)
            listing.setFurnished(dto.getFurnishingLevel());

        // Update Features by IDs
        if (dto.getFeatureIds() != null && !dto.getFeatureIds().isEmpty()) {
            Set<Feature> featureSet = dto.getFeatureIds().stream()
                    .map(featureRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toSet());
            listing.setFeatures(featureSet);
        } else if (dto.getIncludedItems() != null && !dto.getIncludedItems().isEmpty()) {
            // Fallback: Handle feature names if IDs not provided
            Set<Feature> featureSet = new HashSet<>();
            for (String featureName : dto.getIncludedItems()) {
                Feature feature = featureRepository.findByTitleIgnoreCase(featureName)
                        .orElseGet(() -> {
                            log.info("Creating new feature: {}", featureName);
                            Feature newFeature = new Feature();
                            newFeature.setTitle(featureName);
                            newFeature.setAltTag(featureName.toLowerCase().replace(" ", "-"));
                            newFeature.setStatus(true);
                            return featureRepository.save(newFeature);
                        });
                featureSet.add(feature);
            }
            listing.setFeatures(featureSet);
        }

        // ========== UPDATE MEDIA & CONTACT ==========
        if (dto.getVideoUrl() != null)
            listing.setVirtualTour(dto.getVideoUrl());
        if (dto.getOwnershipType() != null)
            listing.setOwnershipType(dto.getOwnershipType());
        if (dto.getReraId() != null)
            listing.setReraId(dto.getReraId());
        if (dto.getReraState() != null)
            listing.setReraState(dto.getReraState());
        if (dto.getContactName() != null)
            listing.setContactName(dto.getContactName());
        if (dto.getContactPhone() != null) {
            listing.setContactPhone(dto.getContactPhone());
        } else if (dto.getPrimaryContact() != null) {
            listing.setContactPhone(dto.getPrimaryContact());
        }
        if (dto.getContactEmail() != null) {
            listing.setContactEmail(dto.getContactEmail());
        } else if (dto.getPrimaryEmail() != null) {
            listing.setContactEmail(dto.getPrimaryEmail());
        }
        if (dto.getContactPreference() != null)
            listing.setContactPreference(dto.getContactPreference());
        if (dto.getPreferredTime() != null)
            listing.setPreferredTime(dto.getPreferredTime());
        if (dto.getAdditionalNotes() != null)
            listing.setAdditionalNotes(dto.getAdditionalNotes());
        if (dto.getTruthfulDeclaration() != null)
            listing.setTruthfulDeclaration(dto.getTruthfulDeclaration());
        if (dto.getDpdpConsent() != null)
            listing.setDpdpConsent(dto.getDpdpConsent());

        // ========== UPDATE RELATIONSHIPS ==========
        // Update City
        if (dto.getCityId() != null) {
            cityRepository.findById(dto.getCityId()).ifPresent(listing::setCity);
        } else if (dto.getCity() != null) {
            City city = cityRepository.findByNameIgnoreCase(dto.getCity())
                    .orElseGet(() -> {
                        log.info("Creating new city: {}", dto.getCity());
                        City newCity = new City();
                        newCity.setName(dto.getCity());
                        newCity.setSlugUrl(generateSlug(dto.getCity()));
                        newCity.setCreatedAt(LocalDateTime.now());
                        newCity.setUpdatedAt(LocalDateTime.now());
                        return cityRepository.save(newCity);
                    });
            listing.setCity(city);
        }

        // Update Builder
        if (dto.getBuilderId() != null) {
            builderRepository.findById(dto.getBuilderId()).ifPresent(listing::setBuilder);
        } else if (dto.getBuilderName() != null && !dto.getBuilderName().isEmpty()) {
            Builder builder = builderRepository.findByBuilderNameIgnoreCase(dto.getBuilderName())
                    .orElseGet(() -> {
                        log.info("Creating new builder: {}", dto.getBuilderName());
                        Builder newBuilder = new Builder();
                        newBuilder.setBuilderName(dto.getBuilderName());
                        newBuilder.setSlugUrl(generateSlug(dto.getBuilderName()));
                        newBuilder.setCreatedAt(LocalDateTime.now());
                        newBuilder.setUpdatedAt(LocalDateTime.now());
                        return builderRepository.save(newBuilder);
                    });
            listing.setBuilder(builder);
        }

        // Update Locality
        if (dto.getLocalityId() != null) {
            localityRepository.findById((long) dto.getLocalityId()).ifPresent(listing::setLocality);
        }

        // Update Listing Type Entity
        if (dto.getListingType() != null) {
            projectTypeRepository.findByProjectTypeNameIgnoreCase(dto.getListingType())
                    .ifPresent(listing::setListingTypeEntity);
        }

        // Update Status Entity
        if (dto.getStatus() != null) {
            projectStatusRepository.findByStatusNameIgnoreCase(dto.getStatus())
                    .ifPresent(listing::setStatusEntity);
        }

        // Update Amenities
        if (dto.getAmenityIds() != null && !dto.getAmenityIds().isEmpty()) {
            Set<Amenity> amenitySet = dto.getAmenityIds().stream()
                    .map(amenityRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toSet());
            listing.setAmenities(amenitySet);
        } else if (dto.getSocietyFeatures() != null && !dto.getSocietyFeatures().isEmpty()) {
            // Handle amenity names if IDs not provided
            Set<Amenity> amenitySet = new HashSet<>();
            for (String amenityName : dto.getSocietyFeatures()) {
                Amenity amenity = amenityRepository.findByTitleIgnoreCase(amenityName)
                        .orElseGet(() -> {
                            log.info("Creating new amenity: {}", amenityName);
                            Amenity newAmenity = new Amenity();
                            newAmenity.setTitle(amenityName);
                            newAmenity.setAltTag(amenityName.toLowerCase().replace(" ", "-"));
                            newAmenity.setStatus(true);
                            return amenityRepository.save(newAmenity);
                        });
                amenitySet.add(amenity);
            }
            listing.setAmenities(amenitySet);
        }

        // Update Nearby Benefits if provided
        if (dto.getNearbyBenefits() != null) {
            // Delete existing nearby benefits
            propertyListingNearbyBenefitRepository.deleteByPropertyListingId(id);
            // Create new ones
            List<PropertyListingNearbyBenefit> nearbyBenefitList = new ArrayList<>();
            for (NearbyBenefitDto benefitDto : dto.getNearbyBenefits()) {
                if (benefitDto.getId() != null && benefitDto.getDistance() != null && benefitDto.getDistance() > 0) {
                    MasterBenefit masterBenefit = masterBenefitRepository.findById(benefitDto.getId())
                            .orElse(null);
                    if (masterBenefit != null) {
                        PropertyListingNearbyBenefit nearbyBenefit = new PropertyListingNearbyBenefit();
                        nearbyBenefit.setPropertyListing(listing);
                        nearbyBenefit.setMasterBenefit(masterBenefit);
                        nearbyBenefit.setDistance(benefitDto.getDistance());
                        nearbyBenefitList.add(nearbyBenefit);
                    }
                }
            }
            if (!nearbyBenefitList.isEmpty()) {
                propertyListingNearbyBenefitRepository.saveAll(nearbyBenefitList);
            }
        }

        // Update approval status if provided
        if (dto.getApprovalStatus() != null && !dto.getApprovalStatus().isEmpty()) {
            try {
                listing.setApprovalStatus(ProjectApprovalStatus.valueOf(dto.getApprovalStatus().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid approval status '{}', keeping existing status", dto.getApprovalStatus());
            }
        }

        // Update isUserSubmitted if provided
        if (dto.getIsUserSubmitted() != null) {
            listing.setIsUserSubmitted(dto.getIsUserSubmitted());
        }

        // Update images if provided
        // Note: Frontend sends only NEW images to upload, existing images are preserved
        // If images array is provided and not empty, add them to existing images
        if (images != null && images.length > 0) {
            // Save new images (existing images are preserved)
            savePropertyImages(listing, images);
        }

        PropertyListing saved = propertyListingRepository.save(listing);
        listingActivityService.record(
                ListingActivityService.SOURCE_PORTAL,
                saved.getId(),
                ListingActivityService.ACTION_UPDATED,
                user,
                ListingActivityService.describeChanges(changes, listingTitle(saved)));
        if (previousStatus != saved.getApprovalStatus()) {
            listingActivityService.record(
                    ListingActivityService.SOURCE_PORTAL,
                    saved.getId(),
                    ListingActivityService.ACTION_STATUS_CHANGED,
                    user,
                    previousStatus + " → " + saved.getApprovalStatus());
        }
        return saved;
    }

    /**
     * Delete property listing
     */
    @Transactional
    public void deletePropertyListing(Long id, User user) {
        // Check if user is admin - admins can delete any listing
        boolean isAdmin = userRoleService.userHasRole(user.getId(), "SUPERADMIN");

        PropertyListing listing;
        if (isAdmin) {
            listing = propertyListingRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Property listing not found"));
        } else {
            listing = propertyListingRepository.findByIdAndUserId(id, user.getId())
                    .orElseThrow(() -> new RuntimeException("Property listing not found or unauthorized"));
        }

        // Delete images
        propertyListingImageRepository.deleteByPropertyListingId(id);

        // Delete listing
        propertyListingRepository.delete(listing);
    }

    /**
     * Approve property listing (admin only)
     */
    @Transactional
    public PropertyListing approvePropertyListing(Long id, User admin) {
        PropertyListing listing = propertyListingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Property listing not found"));

        listing.setApprovalStatus(ProjectApprovalStatus.APPROVED);
        listing.setApprovedBy(admin);
        listing.setApprovedAt(LocalDateTime.now());

        PropertyListing saved = propertyListingRepository.save(listing);
        recordPropertyReviewActivity(
                saved, AdminDashboardActivityService.TASK_PROPERTY_APPROVED);
        listingActivityService.record(
                ListingActivityService.SOURCE_PORTAL,
                saved.getId(),
                ListingActivityService.ACTION_APPROVED,
                admin,
                listingTitle(saved));
        return saved;
    }

    /**
     * Reject property listing (admin only)
     */
    @Transactional
    public PropertyListing rejectPropertyListing(Long id, String reason, User admin) {
        PropertyListing listing = propertyListingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Property listing not found"));

        listing.setApprovalStatus(ProjectApprovalStatus.REJECTED);
        listing.setApprovedBy(admin);
        listing.setApprovedAt(LocalDateTime.now());
        listing.setRejectionReason(reason);

        PropertyListing saved = propertyListingRepository.save(listing);
        recordPropertyReviewActivity(
                saved, AdminDashboardActivityService.TASK_PROPERTY_REJECTED);
        listingActivityService.record(
                ListingActivityService.SOURCE_PORTAL,
                saved.getId(),
                ListingActivityService.ACTION_REJECTED,
                admin,
                reason);
        return saved;
    }

    private void recordPropertyReviewActivity(PropertyListing listing, String taskType) {
        if (listing == null || listing.getId() == null) {
            return;
        }
        String label = listing.getTitle();
        if (label == null || label.isBlank()) {
            label = listing.getProjectName();
        }
        if (label == null || label.isBlank()) {
            label = "Listing #" + listing.getId();
        }
        String prefix =
                AdminDashboardActivityService.TASK_PROPERTY_REJECTED.equals(taskType)
                        ? "Rejected: "
                        : "Approved: ";
        adminDashboardActivityService.recordForCurrentUser(
                taskType,
                prefix + label,
                "/admin/dashboard/property-approvals/" + listing.getId());
    }

    /**
     * Convert PropertyListing entity to DTO
     */
    private PropertyListingDto convertToDto(PropertyListing listing) {
        PropertyListingDto dto = new PropertyListingDto();

        dto.setId(listing.getId());
        dto.setListingType(listing.getListingType());
        dto.setTransaction(listing.getTransaction());
        dto.setSubType(listing.getSubType());
        dto.setTitle(listing.getTitle());
        dto.setDescription(listing.getDescription());
        dto.setStatus(listing.getStatus());
        dto.setPossession(listing.getPossession());
        dto.setOccupancy(listing.getOccupancy());
        dto.setNoticePeriod(listing.getNoticePeriod());

        dto.setProjectName(listing.getProjectName());
        dto.setBuilderName(listing.getBuilderName());
        dto.setBuilderId(listing.getBuilder() != null ? listing.getBuilder().getId() : null);
        dto.setAddress(listing.getAddress());
        dto.setLocality(listing.getLocalityName());
        dto.setLocalityId(listing.getLocality() != null ? (int) listing.getLocality().getId() : null);
        dto.setCity(listing.getCity() != null ? listing.getCity().getName() : null);
        dto.setCityId(listing.getCity() != null ? listing.getCity().getId() : null);
        dto.setPincode(listing.getPincode());
        dto.setLatitude(listing.getLatitude());
        dto.setLongitude(listing.getLongitude());

        dto.setCarpetArea(listing.getCarpetArea());
        dto.setBuiltUpArea(listing.getBuiltUpArea());
        dto.setSuperBuiltUpArea(listing.getSuperBuiltUpArea());
        dto.setPlotArea(listing.getPlotArea());

        dto.setTotalPrice(listing.getTotalPrice());
        dto.setPricePerSqft(listing.getPricePerSqft());
        dto.setMaintenanceCharges(listing.getMaintenanceCharges());
        dto.setBookingAmount(listing.getBookingAmount());

        dto.setFloorNumber(listing.getFloorNumber());
        dto.setTotalFloors(listing.getTotalFloors());
        dto.setFacing(listing.getFacing());
        dto.setAgeOfConstruction(listing.getAgeOfConstruction());

        dto.setBedrooms(listing.getBedrooms());
        dto.setBathrooms(listing.getBathrooms());
        dto.setBalconies(listing.getBalconies());
        dto.setParking(listing.getParking());
        dto.setFurnished(listing.getFurnished());

        // Initialize lazy-loaded features collection before accessing
        if (listing.getFeatures() != null) {
            Hibernate.initialize(listing.getFeatures());
            dto.setFeatureIds(listing.getFeatures().stream()
                    .map(Feature::getId)
                    .collect(Collectors.toList()));
            dto.setFeatureNames(listing.getFeatures().stream()
                    .map(Feature::getTitle)
                    .collect(Collectors.toList()));
        } else {
            dto.setFeatureIds(new ArrayList<>());
            dto.setFeatureNames(new ArrayList<>());
        }

        // Initialize lazy-loaded amenities collection before accessing
        if (listing.getAmenities() != null) {
            Hibernate.initialize(listing.getAmenities());
            dto.setAmenityIds(listing.getAmenities().stream()
                    .map(Amenity::getId)
                    .collect(Collectors.toList()));
            dto.setAmenityNames(listing.getAmenities().stream()
                    .map(Amenity::getTitle)
                    .collect(Collectors.toList()));
        } else {
            dto.setAmenityIds(new ArrayList<>());
            dto.setAmenityNames(new ArrayList<>());
        }

        // Initialize lazy-loaded nearby benefits collection before accessing
        if (listing.getNearbyBenefits() != null) {
            Hibernate.initialize(listing.getNearbyBenefits());
            List<NearbyBenefitResponseDto> nearbyBenefitDtos = listing.getNearbyBenefits().stream()
                    .map(nb -> {
                        NearbyBenefitResponseDto nbDto = new NearbyBenefitResponseDto();
                        nbDto.setId(nb.getMasterBenefit().getId());
                        nbDto.setBenefitName(nb.getMasterBenefit().getBenefitName());
                        nbDto.setBenefitIcon(nb.getMasterBenefit().getBenefitIcon());
                        nbDto.setAltTag(nb.getMasterBenefit().getAltTag());
                        nbDto.setDistance(nb.getDistance());
                        return nbDto;
                    })
                    .collect(Collectors.toList());
            dto.setNearbyBenefits(nearbyBenefitDtos);
        } else {
            dto.setNearbyBenefits(new ArrayList<>());
        }

        // Get image URLs
        List<PropertyListingImage> images = propertyListingImageRepository
                .findByPropertyListingIdOrderByDisplayOrderAsc(listing.getId());
        dto.setImageUrls(images.stream()
                .map(PropertyListingImage::getImageUrl)
                .collect(Collectors.toList()));

        dto.setVirtualTour(listing.getVirtualTour());
        dto.setOwnershipType(listing.getOwnershipType());
        dto.setReraId(listing.getReraId());
        dto.setReraState(listing.getReraState());
        dto.setContactName(listing.getContactName());
        dto.setContactPhone(listing.getContactPhone());
        dto.setContactEmail(listing.getContactEmail());
        dto.setContactPreference(listing.getContactPreference());
        dto.setPreferredTime(listing.getPreferredTime());
        dto.setAdditionalNotes(listing.getAdditionalNotes());
        dto.setTruthfulDeclaration(listing.getTruthfulDeclaration());
        dto.setDpdpConsent(listing.getDpdpConsent());

        dto.setApprovalStatus(listing.getApprovalStatus());
        dto.setRejectionReason(listing.getRejectionReason());
        dto.setCreatedAt(listing.getCreatedAt());
        dto.setUpdatedAt(listing.getUpdatedAt());
        dto.setApprovedAt(listing.getApprovedAt());

        // User Information
        if (listing.getUser() != null) {
            User user = listing.getUser();
            Hibernate.initialize(user.getRoles());
            String listerType = "OWNER";
            if (user.getRoles() != null) {
                boolean isOwner = user.getRoles().stream().anyMatch(role ->
                        role != null && ("OWNER".equalsIgnoreCase(role.getRoleName())
                                || "PROPERTY_OWNER".equalsIgnoreCase(role.getRoleName())));
                boolean isBroker = user.getRoles().stream().anyMatch(role ->
                        role != null && ("BROKER".equalsIgnoreCase(role.getRoleName())
                                || "PROPERTY_BROKER".equalsIgnoreCase(role.getRoleName())));
                if (isBroker && !isOwner) {
                    listerType = "BROKER";
                }
            }
            dto.setListerType(listerType);
            dto.setUserId(user.getId());
            dto.setUserEmail(user.getEmail());
            dto.setUserName(user.getFullName());
            dto.setUserPhone(user.getPhone());
            dto.setUserLocation(user.getLocation());
            dto.setUserBio(user.getBio());
            dto.setUserAvatar(user.getAvatar());
            dto.setUserExperience(user.getExperience());
            dto.setUserRating(user.getRating());
            dto.setUserTotalDeals(user.getTotalDeals());
            dto.setUserVerified(user.getVerified());
            if (user.getCreatedAt() != null) {
                dto.setUserCreatedAt(user.getCreatedAt().toInstant()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime());
            }
        }

        return dto;
    }

    private static List<String> portalChangeSummary(
            PropertyListing listing,
            PropertyListingRequestDto dto,
            MultipartFile[] images) {
        List<String> changes = new ArrayList<>();
        ListingActivityService.addIfProvidedAndChanged(changes, "title", listing.getTitle(), dto.getTitle());
        ListingActivityService.addIfProvidedAndChanged(changes, "price", listing.getTotalPrice(), dto.getTotalPrice());
        ListingActivityService.addIfProvidedAndChanged(changes, "project name", listing.getProjectName(), dto.getProjectName());
        ListingActivityService.addIfProvidedAndChanged(changes, "builder", listing.getBuilderName(), dto.getBuilderName());
        ListingActivityService.addIfProvidedAndChanged(changes, "locality", listing.getLocalityName(), dto.getLocality());
        ListingActivityService.addIfProvidedAndChanged(changes, "bedrooms", listing.getBedrooms(), dto.getBedrooms());
        ListingActivityService.addIfProvidedAndChanged(changes, "listing type", listing.getListingType(), dto.getListingType());
        ListingActivityService.addIfProvidedAndChanged(changes, "transaction", listing.getTransaction(), dto.getTransaction());
        ListingActivityService.addIfProvidedAndChanged(changes, "description", listing.getDescription(), dto.getDescription());
        ListingActivityService.addIfProvidedAndChanged(changes, "carpet area", listing.getCarpetArea(), dto.getCarpetArea());
        ListingActivityService.addIfProvidedAndChanged(changes, "furnishing", listing.getFurnished(), dto.getFurnishingLevel());
        if (dto.getCityId() != null
                && (listing.getCity() == null || !dto.getCityId().equals(listing.getCity().getId()))) {
            changes.add("city");
        }
        if (images != null && images.length > 0) {
            changes.add("images");
        }
        if ((dto.getAmenityIds() != null && !dto.getAmenityIds().isEmpty())
                || (dto.getSocietyFeatures() != null && !dto.getSocietyFeatures().isEmpty())) {
            changes.add("amenities");
        }
        if (dto.getNearbyBenefits() != null) {
            changes.add("nearby benefits");
        }
        return changes;
    }

    /**
     * Generate title from property details
     */
    private String listingTitle(PropertyListing listing) {
        if (listing == null) {
            return null;
        }
        if (listing.getTitle() != null && !listing.getTitle().isBlank()) {
            return listing.getTitle().trim();
        }
        if (listing.getProjectName() != null && !listing.getProjectName().isBlank()) {
            return listing.getProjectName().trim();
        }
        return "Property listing";
    }

    private String generateTitle(PropertyListingRequestDto dto) {
        List<String> parts = new ArrayList<>();
        if (dto.getBedrooms() != null)
            parts.add(dto.getBedrooms() + " BHK");
        if (dto.getSubType() != null)
            parts.add(dto.getSubType());
        if (dto.getLocality() != null)
            parts.add("in " + dto.getLocality());
        if (dto.getCity() != null)
            parts.add(dto.getCity());
        return parts.isEmpty() ? "Property Listing" : String.join(" ", parts);
    }

    /**
     * Generate slug from text
     */
    private String generateSlug(String text) {
        if (text == null) {
            return UUID.randomUUID().toString();
        }
        return text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
