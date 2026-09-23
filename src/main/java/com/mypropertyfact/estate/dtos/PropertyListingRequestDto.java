package com.mypropertyfact.estate.dtos;

import lombok.Data;

import java.util.List;

/**
 * DTO for Property Listing creation/update request
 * Matches the form data structure from the frontend
 */
@Data
public class PropertyListingRequestDto {
    
    // ========== BASIC INFORMATION ==========
    private String listingType;          // Residential or Commercial
    private String transaction;           // Sale or Rent
    private String subType;              // Apartment, Villa, Office, etc.
    private String title;
    private String description;
    private String status;               // Ready/Under-Construction
    private String possession;
    private String occupancy;
    private Integer noticePeriod;
    
    // ========== LOCATION & AREA ==========
    private String projectName;
    private String builderName;
    private Integer builderId;
    private String address;
    private String locality;
    private Integer localityId;
    private String city;
    private Integer cityId;
    private String pinCode;
    private Double latitude;
    private Double longitude;
    
    // Area Details
    private Double carpetArea;
    private Double builtUpArea;
    private Double superBuiltUpArea;
    private Double plotArea;
    
    // ========== PRICING ==========
    private Double totalPrice;
    private Double pricePerSqft;
    private Double maintenanceCam;      // Note: frontend sends "maintenanceCam"
    private Double bookingAmount;
    
    // ========== PROPERTY DETAILS ==========
    private Integer floorNo;             // Note: frontend sends "floorNo"
    private Integer totalFloors;
    private String facing;
    private String unitFacing;           // Same as facing
    private String ageOfConstruction;
    private String ageOfProperty;       // Same as ageOfConstruction
    private Integer carParkingSlots;     // Extracted from parking string
    private String parkingType;          // Full parking string
    private String powerBackup;
    private String waterSupply;
    private String towerBlock;
    
    // ========== CONFIGURATION ==========
    private Integer bedrooms;
    private Integer bathrooms;
    private Integer balconies;
    private String furnishingLevel;      // Note: frontend sends "furnishingLevel"
    private String additionalRooms;      // Features joined as string
    private List<String> includedItems;  // Features as list
    private List<String> societyFeatures; // Amenities as list
    private List<String> pointsOfInterest;
    private List<String> taxesCharges;
    private String restrictions;
    private String renovationHistory;    // Additional notes
    
    // Amenities, Features, and Nearby Benefits
    private List<Integer> amenityIds;
    private List<Long> featureIds;  // Feature IDs from backend
    private List<NearbyBenefitDto> nearbyBenefits;  // Array of {id, distance} objects
    
    // ========== MEDIA & CONTACT ==========
    private String videoUrl;            // Virtual tour URL
    private String ownershipType;
    private String reraId;
    private String reraState;
    private String contactPreference;
    private String primaryContact;       // Note: frontend sends "primaryContact" (phone)
    private String primaryEmail;        // Note: frontend sends "primaryEmail"
    private String contactName;         // Contact name from form
    private String contactPhone;        // Contact phone from form
    private String contactEmail;        // Contact email from form
    private String preferredTime;
    private String additionalNotes;
    private Boolean truthfulDeclaration;
    private Boolean dpdpConsent;
    
    // ========== APPROVAL STATUS ==========
    private String approvalStatus;        // DRAFT, PENDING, APPROVED, REJECTED, REQUIRES_CHANGES
    private Boolean isUserSubmitted;      // Whether the property is submitted for approval
}

