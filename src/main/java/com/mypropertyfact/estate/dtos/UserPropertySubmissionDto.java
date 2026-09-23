package com.mypropertyfact.estate.dtos;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
public class UserPropertySubmissionDto {
    // Basic Information
    private String listingType;          // Residential or Commercial
    private String transaction;           // Sale or Rent
    private String subType;              // Apartment, Villa, etc.
    private String title;
    private String description;
    private String status;               // Ready/Under-Construction
    private String possession;
    private String occupancy;
    private Integer noticePeriod;
    
    // Location
    private String projectName;
    private String builderName;
    private String address;
    private String locality;
    private String city;
    private String state;
    private String pincode;
    
    // Area
    private Double carpetArea;
    private Double builtUpArea;
    private Double superBuiltUpArea;
    private Double plotArea;
    
    // Pricing
    private Double totalPrice;
    private Double pricePerSqFt;
    private Double maintenanceCharges;
    private Double bookingAmount;
    
    // Property Details
    private Integer floor;
    private Integer totalFloors;
    private String facing;
    private String ageOfConstruction;
    
    // Configuration
    private Integer bedrooms;            // BHK
    private Integer bathrooms;
    private Integer balconies;
    private String parking;
    private String furnished;
    private List<Object> amenities;    // List of amenity IDs (Integer) or names (String) - will be converted
    private List<String> features;
    
    // Contact Information
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String preferredTime;
    private String additionalNotes;
    
    // Media & Files (will be handled separately via MultipartFile)
    private List<MultipartFile> images;
}


