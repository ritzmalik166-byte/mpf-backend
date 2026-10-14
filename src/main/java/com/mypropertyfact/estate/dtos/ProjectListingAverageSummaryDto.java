package com.mypropertyfact.estate.dtos;

public record ProjectListingAverageSummaryDto(
        long totalListings,
        long contributors,
        double avgPerContributor,
        double avgPerDay,
        double avgPerContributorPerDay,
        String topContributor,
        long topContributorListings
) {
}
