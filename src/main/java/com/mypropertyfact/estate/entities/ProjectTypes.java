package com.mypropertyfact.estate.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mypropertyfact.estate.config.CatalogCacheEvictionListener;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@ToString(exclude = {"project", "localities"})
@EntityListeners(CatalogCacheEvictionListener.class)
public class ProjectTypes {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private String projectTypeName;
    private String slugUrl;
    private String metaTitle;
    @Lob
    private String metaKeyword;
    @Lob
    private String metaDesc;
    @Lob
    private String projectTypeDesc;
    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "projectTypes", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Project> project;

    @OneToMany(mappedBy = "projectTypes", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Locality> localities;
}
