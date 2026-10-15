package com.mypropertyfact.estate.entities;

import com.mypropertyfact.estate.config.CatalogCacheEvictionListener;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "testimonials")
@EntityListeners(CatalogCacheEvictionListener.class)
public class Testimonial {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 120)
    private String clientName;

    @Column(length = 150)
    private String clientRole;

    @Column
    private Integer projectId;

    @Column(length = 180)
    private String projectName;

    @Column(nullable = false, length = 1500)
    private String testimonialText;

    @Column(nullable = false)
    private Boolean status = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
