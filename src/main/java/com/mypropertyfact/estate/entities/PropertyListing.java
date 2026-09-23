package com.mypropertyfact.estate.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mypropertyfact.estate.enums.ProjectApprovalStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entity for user-submitted property listings from the portal form.
 * This is separate from the Project entity to maintain clear separation
 * between admin-managed projects and user-submitted listings.
 */
@Entity
@Table(
    name = "property_listings",
    indexes = {
        @Index(name = "idx_pl_created_at", columnList = "created_at"),
        @Index(name = "idx_pl_updated_at", columnList = "updated_at"),
        @Index(name = "idx_pl_user_id", columnList = "user_id"),
        @Index(name = "idx_pl_approval_status", columnList = "approval_status")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {
    "user", "city", "builder", "locality", "listingType", "status",
    "images", "amenities", "features"
})
public class PropertyListing {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_uid", length = 100, unique = true)
    private String propertyUid;
    
    // ========== BASIC INFORMATION ==========
    
    @Column(name = "listing_type", length = 50)
    private String listingType; // Residential or Commercial
    
    @Column(name = "transaction_type", length = 50)
    private String transaction; // Sale or Rent
    
    @Column(name = "sub_type", length = 100)
    private String subType; // Apartment, Villa, Office, etc.
    
    @Column(name = "title", length = 500)
    private String title;
    
    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @Column(name = "property_status", length = 100)
    private String status; // Ready, Under-Construction, etc.
    
    @Column(name = "possession", length = 100)
    private String possession;
    
    @Column(name = "occupancy", length = 100)
    private String occupancy;
    
    @Column(name = "notice_period")
    private Integer noticePeriod; // In days
    
    // ========== LOCATION & AREA ==========
    
    @Column(name = "project_name", length = 255)
    private String projectName;
    
    @Column(name = "builder_name", length = 255)
    private String builderName;
    
    @Lob
    @Column(name = "address", columnDefinition = "TEXT")
    private String address;
    
    @Column(name = "locality_name", length = 255)
    private String localityName;
    
    @Column(name = "pincode", length = 10)
    private String pincode;
    
    @Column(name = "latitude")
    private Double latitude;
    
    @Column(name = "longitude")
    private Double longitude;
    
    // Area Details
    @Column(name = "carpet_area")
    private Double carpetArea;
    
    @Column(name = "built_up_area")
    private Double builtUpArea;
    
    @Column(name = "super_built_up_area")
    private Double superBuiltUpArea;
    
    @Column(name = "plot_area")
    private Double plotArea;
    
    // ========== PRICING ==========
    
    @Column(name = "total_price")
    private Double totalPrice;
    
    @Column(name = "price_per_sqft")
    private Double pricePerSqft;
    
    @Column(name = "maintenance_charges")
    private Double maintenanceCharges;
    
    @Column(name = "booking_amount")
    private Double bookingAmount;
    
    // ========== PROPERTY DETAILS ==========
    
    @Column(name = "floor_number")
    private Integer floorNumber;
    
    @Column(name = "total_floors")
    private Integer totalFloors;
    
    @Column(name = "facing", length = 50)
    private String facing;
    
    @Column(name = "age_of_construction", length = 50)
    private String ageOfConstruction;
    
    // ========== CONFIGURATION ==========
    
    @Column(name = "bedrooms")
    private Integer bedrooms;
    
    @Column(name = "bathrooms")
    private Integer bathrooms;
    
    @Column(name = "balconies")
    private Integer balconies;
    
    @Column(name = "parking", length = 100)
    private String parking;
    
    @Column(name = "furnished", length = 50)
    private String furnished;
    
    // ========== MEDIA & CONTACT ==========
    
    @Column(name = "virtual_tour_url", length = 500)
    private String virtualTour;
    
    @Column(name = "ownership_type", length = 100)
    private String ownershipType;
    
    @Column(name = "rera_id", length = 100)
    private String reraId;
    
    @Column(name = "rera_state", length = 100)
    private String reraState;
    
    @Column(name = "contact_name", length = 255)
    private String contactName;
    
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;
    
    @Column(name = "contact_email", length = 255)
    private String contactEmail;
    
    @Column(name = "contact_preference", length = 50)
    private String contactPreference; // Phone, Email, Any
    
    @Column(name = "preferred_time", length = 100)
    private String preferredTime;
    
    @Lob
    @Column(name = "additional_notes", columnDefinition = "TEXT")
    private String additionalNotes;
    
    @Column(name = "truthful_declaration")
    private Boolean truthfulDeclaration = true;
    
    @Column(name = "dpdp_consent")
    private Boolean dpdpConsent = true;
    
    // ========== RELATIONSHIPS ==========
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id")
    @JsonIgnore
    private City city;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "builder_id")
    @JsonIgnore
    private Builder builder;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locality_id")
    @JsonIgnore
    private Locality locality;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_type_id")
    @JsonIgnore
    private ProjectTypes listingTypeEntity; // For listing type (Residential/Commercial)
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_id")
    @JsonIgnore
    private ProjectStatus statusEntity; // For property status
    
    @OneToMany(mappedBy = "propertyListing", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<PropertyListingImage> images = new ArrayList<>();
    
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "property_listing_amenities",
        joinColumns = @JoinColumn(name = "property_listing_id"),
        inverseJoinColumns = @JoinColumn(name = "amenity_id")
    )
    @JsonIgnore
    private Set<Amenity> amenities;
    
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "property_listing_features",
        joinColumns = @JoinColumn(name = "property_listing_id"),
        inverseJoinColumns = @JoinColumn(name = "feature_id")
    )
    @JsonIgnore
    private Set<Feature> features = new HashSet<>();
    
    @OneToMany(mappedBy = "propertyListing", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<PropertyListingNearbyBenefit> nearbyBenefits = new ArrayList<>();
    
    // ========== APPROVAL & TRACKING ==========
    
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 50)
    private ProjectApprovalStatus approvalStatus = ProjectApprovalStatus.PENDING;
    
    @Column(name = "is_user_submitted")
    private Boolean isUserSubmitted = false;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    @JsonIgnore
    private User approvedBy;
    
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
    
    @Lob
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;
    
    // ========== TIMESTAMPS ==========
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

