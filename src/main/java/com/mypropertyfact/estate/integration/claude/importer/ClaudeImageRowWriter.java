package com.mypropertyfact.estate.integration.claude.importer;

import com.mypropertyfact.estate.entities.Project;
import com.mypropertyfact.estate.entities.ProjectDesktopBanner;
import com.mypropertyfact.estate.entities.ProjectGallery;
import com.mypropertyfact.estate.entities.ProjectMobileBanner;
import com.mypropertyfact.estate.integration.claude.repository.ClaudeProjectClaimRepository;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Inserts the image rows of one project created by this job, in a single transaction. */
@Component
@RequiredArgsConstructor
public class ClaudeImageRowWriter {

    @PersistenceContext
    private EntityManager em;

    private final ClaudeProjectClaimRepository claimRepository;

    public record StoredImage(ImageKind kind, String storedName, String altTag) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int insert(String jobId, int projectId, String slug, List<StoredImage> images) {
        // Only a project this job created (its claims carry this job id) and that is still a draft.
        if (claimRepository.findByJobIdAndProjectId(jobId, projectId).isEmpty()) {
            throw new IllegalStateException("Project #" + projectId + " was not created by job " + jobId + "; images refused");
        }
        Project project = em.find(Project.class, projectId);
        if (project == null) {
            throw new IllegalStateException("Project #" + projectId + " no longer exists");
        }
        if (project.isStatus()) {
            throw new IllegalStateException("Project #" + projectId + " is no longer a draft; images refused");
        }
        int count = 0;
        for (StoredImage img : images) {
            switch (img.kind()) {
                case DESKTOP -> {
                    ProjectDesktopBanner b = new ProjectDesktopBanner();
                    b.setProject(project);
                    b.setDesktopImage(img.storedName());
                    b.setDesktopAltTag(img.altTag());
                    em.persist(b);
                }
                case MOBILE -> {
                    ProjectMobileBanner b = new ProjectMobileBanner();
                    b.setProject(project);
                    b.setMobileImage(img.storedName());
                    b.setMobileAltTag(img.altTag());
                    em.persist(b);
                }
                case LOGO -> project.setProjectLogo(img.storedName());
                case LOCATION -> project.setLocationMap(img.storedName());
                case THUMBNAIL -> project.setProjectThumbnail(img.storedName());
                case GALLERY -> {
                    ProjectGallery g = new ProjectGallery();
                    g.setProject(project);
                    g.setImage(img.storedName());
                    g.setSlugUrl(slug);
                    g.setType("gallery");
                    g.setAltTag(img.altTag());
                    em.persist(g);
                }
            }
            count++;
        }
        em.flush();
        return count;
    }
}
