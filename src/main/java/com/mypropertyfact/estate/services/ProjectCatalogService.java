package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.config.CacheNames;
import com.mypropertyfact.estate.dtos.ProjectShortDetails;
import com.mypropertyfact.estate.repositories.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Shared, cached copy of {@link ProjectRepository#findAllProjects()}. Listing, city, builder and
 * project-type pages all derive from this list, so it is loaded once per cache window instead of
 * on every request. The returned list is immutable; callers must copy before sorting.
 */
@Service
@RequiredArgsConstructor
public class ProjectCatalogService {

    private final ProjectRepository projectRepository;

    @Cacheable(CacheNames.PROJECT_SHORT_DETAILS)
    @Transactional(readOnly = true)
    public List<ProjectShortDetails> findAllProjects() {
        List<ProjectShortDetails> rows = projectRepository.findAllProjects();
        return rows == null ? List.of() : List.copyOf(rows);
    }
}
