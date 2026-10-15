package com.mypropertyfact.estate.entities;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mypropertyfact.estate.config.CatalogCacheEvictionListener;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "cities")
@Data
//@ToString(exclude = {"district", "projects", "blogs", "localities"})
@ToString(exclude = {"projects", "blogs", "localities"})
@EntityListeners(CatalogCacheEvictionListener.class)
public class City {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String metaTitle;
    @Lob
    private String metaKeyWords;
    @Lob
    private String metaDescription;
    private String name;
    private String slugUrl;
    @Lob
    private String cityDisc;
    private String cityImage;
    private String monumentName;
    private String monumentImage;
    @Lob
    private String cityHighlights;
    private Boolean isActive = true;
    private Double latitude;
    private Double longitude;
    private Integer pinCode;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    @OneToMany(mappedBy = "city", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<Project> projects;

    @OneToMany(mappedBy = "city", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<Blog> blogs;

//    @ManyToOne(fetch = FetchType.LAZY)
//    @JsonIgnore
//    @JoinColumn(name = "district_id")
//    private District district;
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "state_id")
    private State state;

    @OneToMany(mappedBy = "city", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<Locality> localities;

    @PrePersist
    public void onCreate(){
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
