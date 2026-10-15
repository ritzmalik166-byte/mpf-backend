package com.mypropertyfact.estate.controllers;

import com.mypropertyfact.estate.dtos.PropertyListingDto;
import com.mypropertyfact.estate.dtos.PropertyShortDetailsDto;
import com.mypropertyfact.estate.entities.Enquery;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.services.EnquiryService;
import com.mypropertyfact.estate.services.PropertyListingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public controller for property listings
 * No authentication required - for public property browsing
 */
@RestController
@RequestMapping("/api/v1/public/properties")
@Slf4j
@RequiredArgsConstructor
public class PublicPropertyController {
    
    private final PropertyListingService propertyListingService;
    
    @Autowired
    private EnquiryService enquiryService;
    /**
     * Get all approved property listings (public access)
     * GET /api/public/properties
     */
    @GetMapping
    public ResponseEntity<?> getAllApprovedProperties(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String listingType,
            @RequestParam(required = false) String transaction,
            @RequestParam(required = false) Integer bedrooms,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String subType,
            @RequestParam(required = false) Integer limit) {
        
        try {
            List<PropertyShortDetailsDto> listings = propertyListingService.getApprovedPropertyListings(
                city, listingType, transaction, bedrooms, status, minPrice, maxPrice, subType, limit);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("count", listings.size());
            response.put("properties", listings);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching approved properties: {}", e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch properties: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get property listing by ID (public access)
     * GET /api/public/properties/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPropertyById(@PathVariable Long id) {
        try {
            java.util.Optional<PropertyListingDto> listingDto = propertyListingService.getPropertyListingById(id);
            
            if (listingDto.isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Property listing not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
            }
            
            PropertyListingDto property = listingDto.get();
            
            // Only return approved properties
            if (property.getApprovalStatus() == null || 
                !property.getApprovalStatus().toString().equals("APPROVED")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Property listing not available");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("property", property);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching property by ID: {}", e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch property: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Submit property lead/inquiry (public access)
     * POST /api/public/properties/lead
     */
    @PostMapping("/lead")
    public ResponseEntity<?> submitPropertyLead(@RequestBody Map<String, Object> leadData) {
        try {
            Enquery enquery = new Enquery();
            enquery.setName((String) leadData.get("name"));
            enquery.setEmail((String) leadData.get("email"));
            enquery.setPhone((String) leadData.get("phone"));
            enquery.setMessage((String) leadData.get("message"));
            
            // Set property ID
            if (leadData.get("propertyId") != null) {
                Object propertyIdObj = leadData.get("propertyId");
                if (propertyIdObj instanceof Number) {
                    enquery.setPropertyId(((Number) propertyIdObj).longValue());
                } else if (propertyIdObj instanceof String) {
                    enquery.setPropertyId(Long.parseLong((String) propertyIdObj));
                }
            }
            
            enquery.setEnquiryFrom("Property Detail Page");
            enquery.setPageName("Property Listing");
            enquery.setStatus("New");
            
            Response response = enquiryService.addUpdate(enquery);
            
            Map<String, Object> apiResponse = new HashMap<>();
            if (response.getIsSuccess() == 1) {
                apiResponse.put("success", true);
                apiResponse.put("message", response.getMessage());
            } else {
                apiResponse.put("success", false);
                apiResponse.put("message", response.getMessage());
            }
            
            return ResponseEntity.ok(apiResponse);
            
        } catch (Exception e) {
            log.error("Error submitting property lead: {}", e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to submit inquiry: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}

