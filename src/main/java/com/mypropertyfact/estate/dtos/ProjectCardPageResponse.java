package com.mypropertyfact.estate.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class ProjectCardPageResponse {
    private int page;
    private int limit;
    private long total;
    private int totalPages;
    private List<ProjectListingCardDto> projects = new ArrayList<>();
    private Map<String, Integer> quickFilterCounts = new LinkedHashMap<>();
}
