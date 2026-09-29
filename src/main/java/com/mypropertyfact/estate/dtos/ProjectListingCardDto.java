package com.mypropertyfact.estate.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fields a project listing card renders. Gallery rows are filenames only so the
 * client can build a slide URL without the full project-detail payload.
 */
@Data
@NoArgsConstructor
public class ProjectListingCardDto {
    private int id;
    private String projectName;
    private String projectPrice;
    private String slugURL;
    private String projectLocality;
    private String projectConfiguration;
    private boolean status;
    private String builderName;
    private String projectStatusName;
    private String propertyTypeName;
    private String cityName;
    private String projectAddress;
    private String projectThumbnailImage;
    private String projectLogo;
    private String projectBannerImage;
    private String builderSlug;
    private String citySlug;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<LocationBenefitDto> locationBenefits = new ArrayList<>();
    private List<String> galleryImageNames = new ArrayList<>();

    public static ProjectListingCardDto fromShort(ProjectShortDetails source) {
        ProjectListingCardDto card = new ProjectListingCardDto();
        if (source == null) return card;
        card.id = source.getId();
        card.projectName = source.getProjectName();
        card.projectPrice = source.getProjectPrice();
        card.slugURL = source.getSlugURL();
        card.projectLocality = source.getProjectLocality();
        card.projectConfiguration = source.getProjectConfiguration();
        card.status = source.isStatus();
        card.builderName = source.getBuilderName();
        card.projectStatusName = source.getProjectStatusName();
        card.propertyTypeName = source.getPropertyTypeName();
        card.cityName = source.getCityName();
        card.projectAddress = source.getProjectAddress();
        card.projectThumbnailImage = source.getProjectThumbnailImage();
        card.projectLogo = source.getProjectLogo();
        card.projectBannerImage = source.getProjectBannerImage();
        card.builderSlug = source.getBuilderSlug();
        card.citySlug = source.getCitySlug();
        card.createdAt = source.getCreatedAt();
        card.updatedAt = source.getUpdatedAt();
        return card;
    }
}
