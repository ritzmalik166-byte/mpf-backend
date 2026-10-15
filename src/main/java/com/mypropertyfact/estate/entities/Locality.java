package com.mypropertyfact.estate.entities;

import com.mypropertyfact.estate.config.CatalogCacheEvictionListener;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Table(name = "localities", indexes = {
        @Index(name = "idx_slug", columnList = "slug"),
        @Index(name = "idx_is_active", columnList = "isActive")
})
@Entity
@ToString(exclude = {"city", "zone", "projectTypes"})
@EntityListeners(CatalogCacheEvictionListener.class)
public class Locality {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false, length = 255)
    private String localityName;

    @Column(unique = true, nullable = false)
    private String slug;
    private Double latitude;
    private Double longitude;
    private Integer pinCode;
    @Lob
    private String description;
    private Double averagePricePerSqFt;
    private boolean isActive;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @ManyToOne
    @JoinColumn(name = "zone_id", nullable = true)
    private Zone zone;

    @ManyToOne
    @JoinColumn(name = "locality_category_id", nullable = false)
    private ProjectTypes projectTypes;
}
