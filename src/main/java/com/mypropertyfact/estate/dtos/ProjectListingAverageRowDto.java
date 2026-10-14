package com.mypropertyfact.estate.dtos;

import java.time.LocalDateTime;

public record ProjectListingAverageRowDto(
        String userKey,
        Integer userId,
        String name,
        String email,
        String userRole,
        long totalListings,
        long normalProjects,
        long portalListings,
        long activeListings,
        long pendingListings,
        long rejectedListings,
        long activeDays,
        double avgPerDay,
        double avgPerActiveDay,
        double avgPerWeek,
        double avgPerMonth,
        double shareOfTotal,
        LocalDateTime firstListedAt,
        LocalDateTime lastListedAt,
        String firstListedDate,
        String lastListedDate
) {
}
