package com.mypropertyfact.estate.integration.claude.importer;

import com.mypropertyfact.estate.entities.*;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeProjectClaim;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeDuplicateException;
import com.mypropertyfact.estate.integration.claude.model.ClaudeRowPlan;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService.ProjectRef;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.FloorPlanSpec;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.LocationBenefitSpec;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Isolated draft importer for the Claude connector (decision D1-B). It creates NEW projects only.
 * It never loads an existing project for writing, never updates or deletes any existing row, and
 * never touches an existing builder's fields. It does not call the existing
 * {@code ProjectExcelUploadService}.
 *
 * <p>Each row runs in its own {@code REQUIRES_NEW} transaction, so a failing row rolls back only
 * itself (its claims, its builder if it created one, its project and children).
 */
@Slf4j
@Component
public class ClaudeDraftProjectImporter {

    @PersistenceContext
    private EntityManager em;

    private final ClaudeLookupService lookup;

    public ClaudeDraftProjectImporter(ClaudeLookupService lookup) {
        this.lookup = lookup;
    }

    public record CreatedDraft(int projectId, String slug, int builderId, boolean builderCreated) {
    }

    /** Removes claims whose project was deleted after an earlier Claude import (own table only). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseStaleClaims(List<Long> claimIds) {
        for (Long id : claimIds) {
            ClaudeProjectClaim c = em.find(ClaudeProjectClaim.class, id);
            if (c != null && (c.getProjectId() == null || !lookup.projectExists(c.getProjectId()))) {
                em.remove(c);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CreatedDraft createDraft(ClaudeRowPlan plan, Integer actorUserId, String jobId) {
        // 1. Claim the identity first. A concurrent import of the same slug/RERA/name+builder+city
        //    (on this or another instance) hits the unique index and this row rolls back.
        List<ClaudeProjectClaim> claims = new ArrayList<>();
        for (String key : plan.claimKeys()) {
            claims.add(claim(key, jobId, plan.excelRow));
        }

        // 2. Re-check existing projects inside the transaction (state may have changed since validation).
        recheckExisting(plan);

        // 3. Builder: reuse an existing one untouched, or create the minimum record.
        Builder builder;
        boolean builderCreated = false;
        Optional<Builder> found = findBuilder(plan);
        if (found.isPresent()) {
            builder = found.get();
        } else {
            if (!plan.newBuilder) {
                throw new IllegalStateException("Builder #" + plan.builderId + " no longer exists");
            }
            ClaudeProjectClaim builderClaim = claim("BUILDER:" + plan.builderSlug, jobId, plan.excelRow);
            builder = new Builder();
            builder.setBuilderName(plan.builderName.trim());
            builder.setSlugUrl(plan.builderSlug);
            if (plan.builderDescription != null) {
                builder.setBuilderDesc(plan.builderDescription);
            }
            LocalDateTime now = LocalDateTime.now();
            builder.setCreatedAt(now);
            builder.setUpdatedAt(now);
            em.persist(builder);
            em.flush();
            builderClaim.setBuilderId(builder.getId());
            builderCreated = true;
        }

        // 4. The project. status=false is set on the new object before persist, and nothing in this
        //    class ever sets it to true, so the INSERT itself writes status = 0.
        Project project = new Project();
        project.setStatus(false);
        project.setShowFeaturedProperties(false);
        project.setProjectName(plan.projectName.trim());
        project.setSlugURL(plan.slug);
        project.setBuilder(builder);
        project.setCity(em.getReference(City.class, plan.cityId));
        if (plan.stateId != null) {
            project.setState(em.getReference(State.class, plan.stateId));
        }
        project.setProjectStatus(em.getReference(ProjectStatus.class, plan.projectStatusId));
        project.setProjectTypes(em.getReference(ProjectTypes.class, plan.projectTypeId));
        if (actorUserId != null) {
            project.setCreatedBy(em.getReference(User.class, actorUserId));
        }
        applyScalars(project, plan.projectFields);
        Set<Amenity> amenities = new HashSet<>();
        for (Integer id : plan.amenityIds) {
            amenities.add(em.getReference(Amenity.class, id));
        }
        project.setAmenities(amenities);
        em.persist(project);
        em.flush();

        // 5. Belt and braces: read the inserted row back with SQL; anything but a draft aborts the row.
        Object status = em.createNativeQuery("SELECT status FROM projects WHERE id = ?1")
                .setParameter(1, project.getId()).getSingleResult();
        if (isTrue(status)) {
            throw new IllegalStateException("Project was not inserted as a draft; row rolled back");
        }

        // 6. Children of the NEW project only.
        if (plan.aboutDescription != null) {
            ProjectsAbout about = new ProjectsAbout();
            about.setProject(project);
            about.setLongDesc(plan.aboutDescription);
            em.persist(about);
        }
        if (plan.walkthroughDescription != null) {
            ProjectWalkthrough w = new ProjectWalkthrough();
            w.setProject(project);
            w.setWalkthroughDesc(plan.walkthroughDescription);
            em.persist(w);
        }
        for (FloorPlanSpec fp : plan.floorPlans) {
            FloorPlan f = new FloorPlan();
            f.setProject(project);
            f.setPlanType(fp.planType());
            f.setAreaSqft(fp.areaSqft());
            f.setAreaSqmt(fp.areaSqmt());
            em.persist(f);
        }
        for (LocationBenefitSpec lb : plan.locationBenefits) {
            LocationBenefit b = new LocationBenefit();
            b.setProject(project);
            b.setBenefitName(lb.name());
            b.setDistance(lb.distance() != null ? lb.distance() : "");
            em.persist(b);
        }
        for (ClaudeRowPlan.Faq faq : plan.faqs) {
            ProjectFaqs f = new ProjectFaqs();
            f.setProject(project);
            f.setFaqQuestion(faq.question());
            f.setFaqAnswer(faq.answer());
            f.setSlugUrl(plan.slug);
            em.persist(f);
        }
        for (ClaudeProjectClaim c : claims) {
            c.setProjectId(project.getId());
        }
        em.flush();
        return new CreatedDraft(project.getId(), plan.slug, builder.getId(), builderCreated);
    }

    private ClaudeProjectClaim claim(String key, String jobId, int excelRow) {
        ClaudeProjectClaim c = new ClaudeProjectClaim();
        c.setClaimKey(ClaudeText.truncate(key, 500));
        c.setJobId(jobId);
        c.setExcelRow(excelRow);
        try {
            em.persist(c);
            em.flush();
        } catch (PersistenceException | DataIntegrityViolationException e) {
            throw new ClaudeDuplicateException("Identity " + key.substring(0, key.indexOf(':'))
                    + " is already claimed by another Claude import (concurrent or earlier)", null);
        }
        return c;
    }

    private void recheckExisting(ClaudeRowPlan plan) {
        List<ProjectRef> hits = new ArrayList<>(lookup.projectsBySlug(plan.slug));
        String by = "slug";
        if (hits.isEmpty()) {
            hits.addAll(lookup.projectsByReraKey(plan.reraKey));
            by = "RERA NO";
        }
        if (hits.isEmpty() && plan.builderId != null) {
            hits.addAll(lookup.projectsByNameBuilderCity(plan.projectName, plan.builderId, plan.cityId));
            by = "project name + builder + city";
        }
        if (!hits.isEmpty()) {
            ProjectRef ref = hits.get(0);
            throw new ClaudeDuplicateException("Matches existing project #" + ref.id() + " \"" + ref.name() + "\" by " + by
                    + " (created after validation)", ref.id());
        }
    }

    private Optional<Builder> findBuilder(ClaudeRowPlan plan) {
        if (plan.builderId != null) {
            return Optional.ofNullable(em.find(Builder.class, plan.builderId));
        }
        // A new builder may already have been created by an earlier row of this job (or elsewhere).
        List<Builder> byName = em.createQuery("SELECT b FROM Builder b WHERE LOWER(TRIM(b.builderName)) = :n", Builder.class)
                .setParameter("n", ClaudeText.lookupKey(plan.builderName)).getResultList();
        if (byName.size() == 1) {
            return Optional.of(byName.get(0));
        }
        if (byName.size() > 1) {
            throw new IllegalStateException("Builder name \"" + plan.builderName + "\" became ambiguous");
        }
        List<Builder> bySlug = em.createQuery("SELECT b FROM Builder b WHERE b.slugUrl = :s", Builder.class)
                .setParameter("s", plan.builderSlug).getResultList();
        if (bySlug.size() == 1) {
            return Optional.of(bySlug.get(0));
        }
        if (bySlug.size() > 1) {
            throw new IllegalStateException("Builder slug \"" + plan.builderSlug + "\" became ambiguous");
        }
        return Optional.empty();
    }

    private static void applyScalars(Project p, Map<String, String> f) {
        p.setProjectLocality(f.get("projectLocality"));
        p.setProjectConfiguration(f.get("projectConfiguration"));
        p.setProjectPrice(f.get("projectPrice"));
        p.setReraNo(f.get("reraNo"));
        p.setReraWebsite(f.get("reraWebsite"));
        p.setIvrNo(f.get("ivrNo"));
        p.setMetaTitle(f.get("metaTitle"));
        p.setMetaDescription(f.get("metaDescription"));
        p.setMetaKeyword(f.get("metaKeyword"));
        p.setAmenityDesc(f.get("amenityDesc"));
        p.setFloorPlanDesc(f.get("floorPlanDesc"));
        p.setLocationDesc(f.get("locationDesc"));
    }

    static boolean isTrue(Object dbValue) {
        return ClaudeText.dbTrue(dbValue);
    }
}
