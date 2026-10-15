package com.mypropertyfact.estate.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mypropertyfact.estate.config.CatalogCacheEvictionListener;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@Table(name = "builders")
@ToString(exclude = "projects")
@EntityListeners(CatalogCacheEvictionListener.class)
public class Builder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private String builderName;
    private String slugUrl;
    @Lob
    private String builderDesc;
    private String metaTitle;
    @Lob
    private String metaDesc;
    @Lob
    private String metaKeyword;
    /** File name only; served from /api/v1/get/images/builders/{slugUrl}/{filename} */
    private String builderLogo;
    /** JSON array of file names under the same builder folder (from ZIP upload). */
    @Lob
    private String developerGalleryImagesJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "builder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Project> projects;
}
