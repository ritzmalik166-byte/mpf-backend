package com.mypropertyfact.estate.dtos;

import java.time.LocalDateTime;
import java.util.List;

public record ProjectListingAverageResponse(
        String rangeStart,
        String rangeEnd,
        long days,
        String timezone,
        boolean truncated,
        ProjectListingAverageSummaryDto summary,
        List<ProjectListingAverageRowDto> rows,
        LocalDateTime generatedAt
) {
}
