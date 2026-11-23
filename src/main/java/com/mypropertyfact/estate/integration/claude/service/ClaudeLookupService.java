package com.mypropertyfact.estate.integration.claude.service;

import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Read-only queries against existing tables (projects, builders, cities, state, country,
 * project_status, project_types, amenity). Nothing here writes. Lookups never use the existing
 * repositories' {@code Optional} finders, because those throw when legacy data has duplicates;
 * ambiguity is reported instead.
 */
@Service
public class ClaudeLookupService {

    @PersistenceContext
    private EntityManager em;

    public record Ref(int id, String name) {
    }

    public record CityRef(int id, String name, Integer stateId, String stateName, Integer countryId, String countryName) {
    }

    public record StateRef(int id, String name, Integer countryId, String countryName) {
    }

    public record BuilderRef(int id, String name, String slug) {
    }

    public record ProjectRef(int id, String name, String slug, boolean published, String reraNo) {
    }

    /** All master data needed to validate one workbook, loaded once per validation run. */
    public static final class MasterData {
        public final Map<String, List<Ref>> countries = new HashMap<>();
        public final Map<String, List<StateRef>> states = new HashMap<>();
        public final Map<String, List<CityRef>> cities = new HashMap<>();
        public final Map<String, List<Ref>> statuses = new HashMap<>();
        public final Map<String, List<Ref>> types = new HashMap<>();
        public final Map<String, List<Ref>> amenities = new HashMap<>();
        public final Map<String, List<BuilderRef>> buildersByName = new HashMap<>();
        public final Map<String, List<BuilderRef>> buildersBySlug = new HashMap<>();
    }

    @Transactional(readOnly = true)
    public MasterData loadMasterData() {
        MasterData md = new MasterData();
        for (Object[] r : rows("SELECT c.id, c.countryName FROM Country c")) {
            add(md.countries, ClaudeText.lookupKey(str(r[1])), new Ref(num(r[0]), str(r[1])));
        }
        for (Object[] r : rows("SELECT s.id, s.stateName, co.id, co.countryName FROM State s LEFT JOIN s.country co")) {
            add(md.states, ClaudeText.lookupKey(str(r[1])), new StateRef(num(r[0]), str(r[1]), numOrNull(r[2]), str(r[3])));
        }
        for (Object[] r : rows("SELECT c.id, c.name, s.id, s.stateName, co.id, co.countryName "
                + "FROM City c LEFT JOIN c.state s LEFT JOIN s.country co")) {
            add(md.cities, ClaudeText.lookupKey(str(r[1])),
                    new CityRef(num(r[0]), str(r[1]), numOrNull(r[2]), str(r[3]), numOrNull(r[4]), str(r[5])));
        }
        for (Object[] r : rows("SELECT ps.id, ps.statusName FROM ProjectStatus ps")) {
            add(md.statuses, ClaudeText.lookupKey(str(r[1])), new Ref(num(r[0]), str(r[1])));
        }
        for (Object[] r : rows("SELECT pt.id, pt.projectTypeName FROM ProjectTypes pt")) {
            add(md.types, ClaudeText.lookupKey(str(r[1])), new Ref(num(r[0]), str(r[1])));
        }
        for (Object[] r : rows("SELECT a.id, a.title FROM Amenity a")) {
            add(md.amenities, ClaudeText.amenityKey(str(r[1])), new Ref(num(r[0]), str(r[1])));
        }
        for (Object[] r : rows("SELECT b.id, b.builderName, b.slugUrl FROM Builder b")) {
            BuilderRef ref = new BuilderRef(num(r[0]), str(r[1]), str(r[2]));
            add(md.buildersByName, ClaudeText.lookupKey(ref.name()), ref);
            if (ref.slug() != null && !ref.slug().isBlank()) {
                add(md.buildersBySlug, ref.slug().trim().toLowerCase(Locale.ROOT), ref);
            }
        }
        return md;
    }

    @Transactional(readOnly = true)
    public List<ProjectRef> projectsBySlug(String slug) {
        return projects("WHERE p.slugURL = :v", slug);
    }

    @Transactional(readOnly = true)
    public List<ProjectRef> projectsByReraKey(String reraKey) {
        if (reraKey == null || reraKey.isEmpty()) {
            return List.of();
        }
        // REPLACE(...) removes ordinary spaces; reraKey has all whitespace removed and is upper-case.
        return projects("WHERE UPPER(REPLACE(p.reraNo, ' ', '')) = :v", reraKey);
    }

    @Transactional(readOnly = true)
    public List<ProjectRef> projectsByNameBuilderCity(String projectName, Integer builderId, Integer cityId) {
        if (builderId == null || cityId == null || projectName == null) {
            return List.of();
        }
        List<Object[]> list = em.createQuery("SELECT p.id, p.projectName, p.slugURL, p.status, p.reraNo FROM Project p "
                        + "WHERE LOWER(TRIM(p.projectName)) = :name AND p.builder.id = :b AND p.city.id = :c", Object[].class)
                .setParameter("name", ClaudeText.lookupKey(projectName))
                .setParameter("b", builderId)
                .setParameter("c", cityId)
                .getResultList();
        return toProjectRefs(list);
    }

    @Transactional(readOnly = true)
    public boolean projectExists(int projectId) {
        Long n = em.createQuery("SELECT COUNT(p) FROM Project p WHERE p.id = :id", Long.class)
                .setParameter("id", projectId).getSingleResult();
        return n != null && n > 0;
    }

    private List<ProjectRef> projects(String where, String value) {
        List<Object[]> list = em.createQuery(
                        "SELECT p.id, p.projectName, p.slugURL, p.status, p.reraNo FROM Project p " + where, Object[].class)
                .setParameter("v", value)
                .getResultList();
        return toProjectRefs(list);
    }

    private static List<ProjectRef> toProjectRefs(List<Object[]> list) {
        List<ProjectRef> out = new ArrayList<>();
        for (Object[] r : list) {
            out.add(new ProjectRef(num(r[0]), str(r[1]), str(r[2]), Boolean.TRUE.equals(r[3]), str(r[4])));
        }
        return out;
    }

    private List<Object[]> rows(String jpql) {
        return em.createQuery(jpql, Object[].class).getResultList();
    }

    private static <T> void add(Map<String, List<T>> map, String key, T value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int num(Object o) {
        return ((Number) o).intValue();
    }

    private static Integer numOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
