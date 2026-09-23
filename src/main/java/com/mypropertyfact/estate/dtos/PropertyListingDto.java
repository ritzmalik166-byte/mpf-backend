package com.mypropertyfact.estate.dtos;

import com.mypropertyfact.estate.enums.ProjectApprovalStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for Property Listing response
 * Used when returning property listing data to the frontend
 */
@Data
public class PropertyListingDto {
    private Long id;
    
    // Basic Information
    private String listingType;
    private String transaction;
    private String subType;
    private String title;
    private String description;
    private String status;
    private String possession;
    private String occupancy;
    private Integer noticePeriod;
    
    // Location & Area
    private String projectName;
    private String builderName;
    private Integer builderId;
    private String address;
    private String locality;
    private Integer localityId;
    private String city;
    private Integer cityId;
    private String pincode;
    private Double latitude;
    private Double longitude;
    
    // Area Details
    private Double carpetArea;
    private Double builtUpArea;
    private Double superBuiltUpArea;
    private Double plotArea;
    
    // Pricing
    private Double totalPrice;
    private Double pricePerSqft;
    private Double maintenanceCharges;
    private Double bookingAmount;
    
    // Property Details
    private Integer floorNumber;
    private Integer totalFloors;
    private String facing;
    private String ageOfConstruction;
    
    // Configuration
    private Integer bedrooms;
    private Integer bathrooms;
    private Integer balconies;
    private String parking;
    private String furnished;
    private List<Long> featureIds;  // Feature IDs
    private List<String> featureNames;  // Feature names
    private List<Integer> amenityIds;
    private List<String> amenityNames;
    private List<NearbyBenefitResponseDto> nearbyBenefits;  // Nearby benefits with distances
    
    // Media & Contact
    private List<String> imageUrls;
    private String virtualTour;
    private String ownershipType;
    private String reraId;
    private String reraState;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String contactPreference;
    private String preferredTime;
    private String additionalNotes;
    private Boolean truthfulDeclaration;
    private Boolean dpdpConsent;
    
    // Approval & Tracking
    private ProjectApprovalStatus approvalStatus;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime approvedAt;
    
    // User Info
    /** OWNER or BROKER — who posted this listing on the portal. */
    private String listerType;
    private Integer userId;
    private String userEmail;
    private String userName;
    private String userPhone;
    private String userLocation;
    private String userBio;
    private String userAvatar;
    private String userExperience;
    private Double userRating;
    private Integer userTotalDeals;
    private Boolean userVerified;
    private LocalDateTime userCreatedAt;
}


