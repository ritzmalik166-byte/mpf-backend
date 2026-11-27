package com.mypropertyfact.estate.integration.claude.service;

import com.mypropertyfact.estate.integration.claude.model.ClaudeRowPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads a created project back from the database (not from the objects that were saved) and
 * checks it against the row plan: still a draft, identity fields, relations and child counts.
 */
@Service
public class ClaudeImportVerificationService {

    @PersistenceContext
    private EntityManager em;

    public record Verification(boolean found, boolean draft, List<String> problems) {
        public boolean ok() {
            return found && draft && problems.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public Verification verify(int projectId, ClaudeRowPlan plan, int expectedImages, int importedImages) {
        List<String> problems = new ArrayList<>();
        List<?> rows = em.createNativeQuery(
                        "SELECT status, project_name, slugurl, builder_id, city_id, project_status_id, property_type, rera_no "
                                + "FROM projects WHERE id = ?1")
                .setParameter(1, projectId).getResultList();
        if (rows.isEmpty()) {
            return new Verification(false, false, List.of("Project #" + projectId + " not found after import"));
        }
        Object[] r = (Object[]) rows.get(0);
        boolean draft = !com.mypropertyfact.estate.integration.claude.util.ClaudeText.dbTrue(r[0]);
        if (!draft) {
            problems.add("Project is published (status=true); expected a draft");
        }
        check(problems, "project name", plan.projectName == null ? null : plan.projectName.trim(), r[1]);
        check(problems, "slug", plan.slug, r[2]);
        if (plan.builderId != null) {
            check(problems, "builder", plan.builderId, num(r[3]));
        }
        check(problems, "city", plan.cityId, num(r[4]));
        check(problems, "project status", plan.projectStatusId, num(r[5]));
        check(problems, "property type", plan.projectTypeId, num(r[6]));
        check(problems, "RERA NO", plan.projectFields.get("reraNo"), r[7]);

        count(problems, "FAQs", plan.faqs.size(), "SELECT COUNT(*) FROM project_faqs WHERE project_id = ?1", projectId);
        count(problems, "floor plans", plan.floorPlans.size(), "SELECT COUNT(*) FROM floor_plans WHERE project_id = ?1", projectId);
        count(problems, "location benefits", plan.locationBenefits.size(),
                "SELECT COUNT(*) FROM location_benefits WHERE project_id = ?1", projectId);
        count(problems, "amenities", plan.amenityIds.size(), "SELECT COUNT(*) FROM project_amenities WHERE project_id = ?1", projectId);
        if (importedImages < expectedImages) {
            problems.add("Images imported " + importedImages + " of " + expectedImages);
        }
        return new Verification(true, draft, problems);
    }

    private void count(List<String> problems, String what, int expected, String sql, int projectId) {
        Object n = em.createNativeQuery(sql).setParameter(1, projectId).getSingleResult();
        int actual = ((Number) n).intValue();
        if (actual != expected) {
            problems.add(what + ": expected " + expected + ", found " + actual);
        }
    }

    private static void check(List<String> problems, String what, Object expected, Object actual) {
        if (!Objects.equals(expected == null ? null : expected.toString(), actual == null ? null : actual.toString())) {
            problems.add(what + " mismatch: expected \"" + expected + "\", found \"" + actual + "\"");
        }
    }

    private static Integer num(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
