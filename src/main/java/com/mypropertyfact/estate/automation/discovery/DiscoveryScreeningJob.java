package com.mypropertyfact.estate.automation.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Optional offline intake for approved source-candidate JSON.
 * Reads every project (unpublished drafts included) with a read-only SELECT. NEVER saves projects or calls
 * import/publish. Writes only the local report JSON.
 *
 * <p>Match levels (all are review signals; nothing is imported either way):
 * <ul>
 *   <li>EXISTING_PROJECT_MATCH: same RERA registration (base UPRERAPRJ number), or same name + builder + city</li>
 *   <li>POSSIBLE_EXISTING_PROJECT: same name + city (builder unknown/different), similar name in the same city,
 *       or same name in another city</li>
 * </ul>
 */
@Component
public class DiscoveryScreeningJob {
    static final int MAX_MATCHES_PER_CANDIDATE = 10;
    private static final int MIN_SIMILAR_NAME_LENGTH = 6;

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final Path input;
    private final Path output;

    public DiscoveryScreeningJob(EntityManager em, ObjectMapper mapper,
            @Value("${mpf.discovery.screening.input:./discovery-candidates.json}") String input,
            @Value("${mpf.discovery.screening.output:./discovery-screening-report.json}") String output) {
        this.em = em;
        this.mapper = mapper;
        this.input = Path.of(input);
        this.output = Path.of(output);
    }

    /** One existing project, scalar fields only. */
    record ExistingProject(int id, String name, String rera, String slug, String builder, String city, boolean published) { }

    /** One match between a candidate and an existing project. */
    record Match(String matchType, String reason, ExistingProject project) { }

    public Path inputPath() { return input; }
    public Path outputPath() { return output; }

    @Transactional(readOnly = true)
    public Map<String, Object> screen() throws Exception {
        if (!Files.isRegularFile(input)) throw new IllegalStateException("Discovery input file missing: " + input);
        DiscoveryCandidate[] candidates = mapper.readValue(Files.readAllBytes(input), DiscoveryCandidate[].class);
        if (candidates.length > 250) throw new IllegalArgumentException("Max 250 candidates per batch");
        // All records, including unpublished drafts. Query scalar fields only; no mutations.
        List<Object[]> rows = em.createQuery("""
            select p.id, p.projectName, p.reraNo, p.slugURL, b.builderName, c.name, p.status
            from Project p left join p.builder b left join p.city c
            """, Object[].class).getResultList();
        List<ExistingProject> existing = rows.stream().map(r -> new ExistingProject(
                ((Number) r[0]).intValue(), (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                Boolean.TRUE.equals(r[6]))).toList();
        long drafts = existing.stream().filter(p -> !p.published()).count();

        List<Map<String, Object>> reports = new ArrayList<>();
        for (DiscoveryCandidate c : candidates) {
            List<String> reasons = new ArrayList<>(DiscoveryScreeningRules.screen(c));
            List<Match> matches = c == null ? List.of() : findMatches(c, existing);
            matches.stream().map(Match::reason).distinct().forEach(reasons::add);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sourceUrl", c == null || c.sourceUrl() == null ? "" : c.sourceUrl());
            entry.put("projectName", c == null || c.projectName() == null ? "" : c.projectName());
            entry.put("reraNumber", c == null || c.reraNumber() == null ? "" : c.reraNumber());
            entry.put("state", "REVIEW_REQUIRED");
            entry.put("databaseResult", matches.isEmpty() ? "NO_MATCH_FOUND"
                    : matches.stream().anyMatch(m -> m.reason().equals("EXISTING_PROJECT_MATCH")) ? "EXISTING_PROJECT_MATCH"
                    : "POSSIBLE_EXISTING_PROJECT");
            entry.put("reasons", reasons.stream().distinct().toList());
            entry.put("matches", matches.stream().map(DiscoveryScreeningJob::matchJson).toList());
            reports.add(entry);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("mode", "READ_ONLY");
        report.put("databaseScreened", true);
        report.put("projectsScreened", existing.size());
        report.put("unpublishedDraftsScreened", drafts);
        report.put("candidateCount", candidates.length);
        report.put("candidates", reports);

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("MPF discovery screening: " + candidates.length + " candidates vs " + existing.size()
                + " projects (" + drafts + " drafts); report=" + output);
        return report;
    }

    /** Pure matching (unit-tested). Strongest matches first, at most MAX_MATCHES_PER_CANDIDATE. */
    static List<Match> findMatches(DiscoveryCandidate c, List<ExistingProject> existing) {
        Set<String> rera = DiscoveryScreeningRules.reraKeys(c.reraNumber());
        String name = DiscoveryScreeningRules.normalize(c.projectName());
        String builder = DiscoveryScreeningRules.normalize(c.builderName());
        String city = DiscoveryScreeningRules.normalize(c.city());
        List<Match> strong = new ArrayList<>();
        List<Match> possible = new ArrayList<>();
        for (ExistingProject p : existing) {
            String pName = DiscoveryScreeningRules.normalize(p.name());
            String pCity = DiscoveryScreeningRules.normalize(p.city());
            String pBuilder = DiscoveryScreeningRules.normalize(p.builder());
            boolean sameCity = !city.isEmpty() && city.equals(pCity);
            if (!rera.isEmpty() && !Collections.disjoint(rera, DiscoveryScreeningRules.reraKeys(p.rera()))) {
                strong.add(new Match("RERA", "EXISTING_PROJECT_MATCH", p));
            } else if (!name.isEmpty() && name.equals(pName) && !builder.isEmpty() && builder.equals(pBuilder) && sameCity) {
                strong.add(new Match("NAME_BUILDER_CITY", "EXISTING_PROJECT_MATCH", p));
            } else if (!name.isEmpty() && name.equals(pName) && sameCity) {
                possible.add(new Match("NAME_CITY", "POSSIBLE_EXISTING_PROJECT", p));
            } else if (sameCity && similar(name, pName)) {
                possible.add(new Match("SIMILAR_NAME_SAME_CITY", "POSSIBLE_EXISTING_PROJECT", p));
            } else if (!name.isEmpty() && name.equals(pName)) {
                possible.add(new Match("SAME_NAME_OTHER_CITY", "POSSIBLE_EXISTING_PROJECT", p));
            }
        }
        List<Match> all = new ArrayList<>(strong);
        all.addAll(possible);
        return all.size() > MAX_MATCHES_PER_CANDIDATE ? all.subList(0, MAX_MATCHES_PER_CANDIDATE) : all;
    }

    /** One normalized name contains the other (e.g. "adityaelysia" vs "adityaelysiapratapvihar"). */
    static boolean similar(String a, String b) {
        if (a.length() < MIN_SIMILAR_NAME_LENGTH || b.length() < MIN_SIMILAR_NAME_LENGTH || a.equals(b)) return false;
        return a.contains(b) || b.contains(a);
    }

    private static Map<String, Object> matchJson(Match m) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("matchType", m.matchType());
        j.put("existingProjectId", m.project().id());
        j.put("existingProjectName", m.project().name() == null ? "" : m.project().name());
        j.put("existingSlug", m.project().slug() == null ? "" : m.project().slug());
        j.put("existingReraNo", m.project().rera() == null ? "" : m.project().rera());
        j.put("existingCity", m.project().city() == null ? "" : m.project().city());
        j.put("publication", m.project().published() ? "PUBLISHED" : "UNPUBLISHED_DRAFT");
        return j;
    }
}
