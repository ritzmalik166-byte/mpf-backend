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
@Table(name = "state")
@ToString(exclude = {"country"})
@EntityListeners(CatalogCacheEvictionListener.class)
public class State {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private String stateName;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    @JsonIgnore
    private Country country;

    @PrePersist
    public void onCreate(){
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

//    @OneToMany(mappedBy = "state", cascade = CascadeType.ALL)
//    @JsonIgnore
//    private List<District> districts;

    @OneToMany(mappedBy = "state", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<City> cities;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
