package com.mypropertyfact.estate.integration.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.entities.*;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeJobResponse;
import com.mypropertyfact.estate.integration.claude.dto.ClaudePropertyResult;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeIntegrationJob;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeJobStatus;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeDuplicateException;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeDraftProjectImporter;
import com.mypropertyfact.estate.integration.claude.importer.ClaudeImageRowWriter;
import com.mypropertyfact.estate.integration.claude.model.ClaudeRowPlan;
import com.mypropertyfact.estate.integration.claude.repository.ClaudeIntegrationJobRepository;
import com.mypropertyfact.estate.integration.claude.scheduler.ClaudeJobRecovery;
import com.mypropertyfact.estate.integration.claude.service.ClaudeJobService;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;
import com.mypropertyfact.estate.integration.claude.storage.ClaudeTemporaryStorageService;
import com.mypropertyfact.estate.integration.claude.validator.ClaudeBatchValidator;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.MasterRoleRepository;
import com.mypropertyfact.estate.services.JwtService;
import com.mypropertyfact.estate.services.ProjectExcelUploadService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests against a DISPOSABLE local MySQL/MariaDB schema {@code mpf_claude_it}
 * (created on demand, rebuilt with ddl-auto=create-drop). Never point this at a real database:
 * the class refuses to run unless the connected schema is {@code mpf_claude_it}.
 *
 * <p>Run with: {@code CLAUDE_IT=true ./mvnw test -Dtest=ClaudeIntegrationDbTest}
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_IT", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/mpf_claude_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "upload_dir=target/claude-it-uploads/",
        "claude.integration.enabled=true",
        "claude.integration.allowed-emails=claude-it@example.com",
        "claude.integration.temp-dir=target/claude-it-tmp",
        "claude.integration.rate-limit-per-minute=1000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeIntegrationDbTest {

    private static final String SCHEMA = "mpf_claude_it";
    private static final String API = "/api/v1/integrations/claude";
    private static final String PASSWORD = "It-Passw0rd!";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MasterRoleRepository roleRepository;
    @Autowired JwtService jwtService;
    @Autowired ClaudeJobService jobService;
    @Autowired ClaudeIntegrationJobRepository jobRepository;
    @Autowired ClaudeBatchValidator validator;
    @Autowired ClaudeDraftProjectImporter importer;
    @Autowired ClaudeImageRowWriter imageRowWriter;
    @Autowired ClaudeJobRecovery recovery;
    @Autowired ClaudeTemporaryStorageService storage;
    @Autowired ProjectExcelUploadService existingExcelImporter;
    @Autowired @Qualifier("claudeImportExecutor") ThreadPoolTaskExecutor claudeExecutor;
    @Autowired ObjectMapper objectMapper;
    @PersistenceContext EntityManager em;

    /**
     * Existing startup runner that cannot complete on a FRESH schema (its caught ALTER TABLE failure
     * still marks the transaction rollback-only). Replaced by a no-op only in this disposable test
     * context; the class itself is not modified. See CLAUDE_INTEGRATION_TEST_REPORT.md.
     */
    @org.springframework.boot.test.mock.mockito.MockBean
    com.mypropertyfact.estate.config.BlogStatusCheckConstraintRunner blogStatusCheckConstraintRunner;

    private int integrationUserId;
    private int publishedId;
    private int draftId;
    private int customSlugId;
    private int rahejaBuilderId;
    private String integrationToken;
    private String staffToken;

    // ------------------------------------------------------------------ setup

    @BeforeAll
    void setUp() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            Assumptions.assumeTrue(SCHEMA.equals(c.getCatalog()), "Refusing to run outside the disposable schema");
            assertThat(c.getCatalog()).isEqualTo(SCHEMA);
        }
        // Test-owned upload folder: start empty so file counts belong to this run only.
        Path uploads = Paths.get("target/claude-it-uploads").toAbsolutePath();
        if (Files.exists(uploads)) {
            try (var walk = Files.walk(uploads)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        tx.executeWithoutResult(s -> seed());
        jdbc.execute("DROP TABLE IF EXISTS claude_it_project_audit");
        jdbc.execute("CREATE TABLE claude_it_project_audit (id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, op VARCHAR(10), "
                + "status_value INT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("DROP TRIGGER IF EXISTS claude_it_projects_ai");
        jdbc.execute("DROP TRIGGER IF EXISTS claude_it_projects_au");
        jdbc.execute("CREATE TRIGGER claude_it_projects_ai AFTER INSERT ON projects FOR EACH ROW "
                + "INSERT INTO claude_it_project_audit(project_id, op, status_value) VALUES (NEW.id, 'INSERT', NEW.status + 0)");
        jdbc.execute("CREATE TRIGGER claude_it_projects_au AFTER UPDATE ON projects FOR EACH ROW "
                + "INSERT INTO claude_it_project_audit(project_id, op, status_value) VALUES (NEW.id, 'UPDATE', NEW.status + 0)");
        integrationToken = login("claude-it@example.com", "claude-it");
        staffToken = login("staff-it@example.com", "staff-it");
    }

    private void seed() {
        Country india = persist(new Country(), c -> c.setCountryName("India"));
        State up = persist(new State(), s -> { s.setStateName("Uttar Pradesh"); s.setCountry(india); });
        State haryana = persist(new State(), s -> { s.setStateName("Haryana"); s.setCountry(india); });
        persist(new City(), c -> { c.setName("Ghaziabad"); c.setSlugUrl("ghaziabad"); c.setState(up); });
        City gurugram = persist(new City(), c -> { c.setName("Gurugram"); c.setSlugUrl("gurugram"); c.setState(haryana); });
        ProjectStatus ready = persist(new ProjectStatus(), p -> p.setStatusName("Ready To Move"));
        persist(new ProjectStatus(), p -> p.setStatusName("Under Construction"));
        ProjectTypes residential = persist(new ProjectTypes(), t -> { t.setProjectTypeName("Residential"); t.setSlugUrl("residential"); });
        for (String a : List.of("Swimming Pool", "Gym", "Wi-fi Connectivity", "Children's Play Area")) {
            persist(new Amenity(), x -> x.setTitle(a));
        }
        Builder raheja = persist(new Builder(), b -> {
            b.setBuilderName("Raheja Developers");
            b.setSlugUrl("raheja-developers");
            b.setBuilderDesc("ORIGINAL BUILDER DESCRIPTION");
            b.setBuilderLogo("raheja-logo.png");
            b.setCreatedAt(LocalDateTime.of(2024, 1, 1, 10, 0));
            b.setUpdatedAt(LocalDateTime.of(2024, 1, 1, 10, 0));
        });
        rahejaBuilderId = raheja.getId();
        Project published = project("Existing Published Tower", "existing-published-tower", "PUB-RERA-1", true, raheja, gurugram, ready, residential);
        Project draft = project("Existing Draft Villas", "existing-draft-villas", "DRAFT-RERA-2", false, raheja, gurugram, ready, residential);
        Project custom = project("Gamma Residency", "gamma-residency-custom", null, true, raheja, gurugram, ready, residential);
        publishedId = published.getId();
        draftId = draft.getId();
        customSlugId = custom.getId();
        for (Project p : List.of(published, draft)) {
            ProjectFaqs f = new ProjectFaqs();
            f.setProject(p);
            f.setFaqQuestion("Original question?");
            f.setFaqAnswer("Original answer.");
            f.setSlugUrl(p.getSlugURL());
            em.persist(f);
            FloorPlan fp = new FloorPlan();
            fp.setProject(p);
            fp.setPlanType("3 BHK");
            fp.setAreaSqft(1500.0);
            em.persist(fp);
        }
        MasterRole admin = roleRepository.findByRoleNameIgnoreCase("ADMIN").orElseGet(() -> persist(new MasterRole(), r -> {
            r.setRoleName("ADMIN");
            r.setIsActive(true);
        }));
        integrationUserId = user("claude-it@example.com", "claude-it", admin).getId();
        user("staff-it@example.com", "staff-it", admin);
    }

    private User user(String email, String dashboard, MasterRole role) {
        return persist(new User(), u -> {
            u.setFullName(dashboard);
            u.setEmail(email);
            u.setDashboardUsername(dashboard);
            u.setPassword(passwordEncoder.encode(PASSWORD));
            u.setRoles(new HashSet<>(Set.of(role)));
            u.setAdminPermissions(new HashSet<>(Set.of("MANAGE_PROJECTS")));
            u.setAdminStaffApproved(true);
            u.setEnabled(true);
            u.setTokenVersion(0);
        });
    }

    private Project project(String name, String slug, String rera, boolean published, Builder b, City c, ProjectStatus s, ProjectTypes t) {
        return persist(new Project(), p -> {
            p.setProjectName(name);
            p.setSlugURL(slug);
            p.setReraNo(rera);
            p.setStatus(published);
            p.setBuilder(b);
            p.setCity(c);
            p.setProjectStatus(s);
            p.setProjectTypes(t);
            p.setMetaTitle("ORIGINAL META " + name);
            p.setCreatedAt(LocalDateTime.of(2024, 1, 1, 10, 0));
        });
    }

    private <T> T persist(T entity, java.util.function.Consumer<T> init) {
        init.accept(entity);
        em.persist(entity);
        em.flush();
        return entity;
    }

    private String login(String email, String dashboard) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/admin-portal/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD, "dashboardUsername", dashboard))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    // ------------------------------------------------------------------ helpers

    private MockMultipartHttpServletRequestBuilder upload(String path, byte[] excel, byte[] zip) {
        MockMultipartHttpServletRequestBuilder b = multipart(API + path)
                .file(new MockMultipartFile("excelFile", "properties.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel));
        if (zip != null) {
            b.file(new MockMultipartFile("imagesZip", "properties_images.zip", "application/zip", zip));
        }
        return b;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private ClaudeJobResponse awaitJob(String jobId) throws Exception {
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end) {
            ClaudeJobResponse r = jobService.response(jobId);
            if (ClaudeJobStatus.valueOf(r.getStatus()).isTerminal()) {
                return r;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Job did not finish: " + jobId);
    }

    private Map<String, Object> snapshotProject(int id) {
        Map<String, Object> s = new TreeMap<>(jdbc.queryForMap("SELECT * FROM projects WHERE id = ?", id));
        for (String table : List.of("project_faqs", "floor_plans", "location_benefits", "project_gallery", "project_desktop_banners",
                "project_mobile_banners", "project_amenities", "projects_about", "project_walkthrough")) {
            String col = table.equals("project_walkthrough") ? "project_Id" : "project_id";
            s.put("#" + table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ?", Integer.class, id));
        }
        s.put("#faq_text", jdbc.queryForList("SELECT CONCAT(faq_question, '|', faq_answer) FROM project_faqs WHERE project_id = ? ORDER BY id",
                String.class, id));
        return s;
    }

    private Map<String, Object> snapshotBuilder(int id) {
        return new TreeMap<>(jdbc.queryForMap("SELECT * FROM builders WHERE id = ?", id));
    }

    private Map<String, Object> row(String name, String builder, String city, String state, String rera) {
        Map<String, Object> r = ClaudeTestFixtures.validRow(name, builder, city, state, rera);
        return r;
    }

    private byte[] imagesZipFor(String... projectNames) throws Exception {
        Path dir = Files.createTempDirectory("claude-it-zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : projectNames) {
            String key = name.replace(' ', '-');
            entries.put(key + "/" + key + "_Desktop-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 2508, 1200));
            entries.put(key + "/" + key + "_Mobile-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 800, 800));
            entries.put(key + "/" + key + "_Thumbnail_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 800, 800));
            entries.put(key + "/" + key + "_Logo_My-Property-Fact.png", ClaudeTestFixtures.image("png", 792, 203));
            entries.put(key + "/" + key + "_Gallery-1_My-Property-Fact.jpg", ClaudeTestFixtures.image("jpg", 1600, 1200));
        }
        return Files.readAllBytes(ClaudeTestFixtures.zip(dir, "imgs.zip", entries));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ------------------------------------------------------------------ authentication

    @Test
    void authenticationAndAuthorization() throws Exception {
        mvc.perform(get(API + "/template").param("format", "json").header("Authorization", bearer(integrationToken)))
                .andExpect(status().isOk());
        mvc.perform(get(API + "/template").param("format", "json")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin-portal/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"claude-it@example.com\",\"password\":\"wrong\",\"dashboardUsername\":\"claude-it\"}"))
                .andExpect(status().isUnauthorized());
        User u = em.find(User.class, integrationUserId);
        String expired = jwtService.generateToken(new HashMap<>(Map.of("tokenVersion", 0)), u, -60_000L);
        mvc.perform(get(API + "/template").param("format", "json").header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized());
        // MANAGE_PROJECTS but not on the allow-list.
        mvc.perform(get(API + "/template").param("format", "json").header("Authorization", bearer(staffToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void integrationAccountIsBlockedFromExistingWriteApis() throws Exception {
        Map<String, Object> before = snapshotProject(publishedId);
        MvcResult r = mvc.perform(delete("/api/v1/projects/delete/" + publishedId).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("INTEGRATION_ACCOUNT_RESTRICTED");
        mvc.perform(multipart("/api/v1/excel-upload/projects").file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", new byte[]{1}))
                .header("Authorization", bearer(integrationToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/admin/get-by-id/" + publishedId).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isOk());
        assertThat(snapshotProject(publishedId)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ validate

    @Test
    void validateEndpointWritesNothing() throws Exception {
        int projects = count("SELECT COUNT(*) FROM projects");
        int builders = count("SELECT COUNT(*) FROM builders");
        int jobs = count("SELECT COUNT(*) FROM claude_integration_job");
        MvcResult r = mvc.perform(upload("/properties/validate", ClaudeTestFixtures.referenceWorkbook(), null)
                .header("Authorization", bearer(integrationToken))).andExpect(status().isOk()).andReturn();
        JsonNode report = objectMapper.readTree(r.getResponse().getContentAsString());
        assertThat(report.get("totalRows").asInt()).isEqualTo(20);
        assertThat(report.get("valid").asBoolean()).isFalse();
        assertThat(report.get("workbook").get("detectedHeaders")).hasSize(32);
        assertThat(count("SELECT COUNT(*) FROM projects")).isEqualTo(projects);
        assertThat(count("SELECT COUNT(*) FROM builders")).isEqualTo(builders);
        assertThat(count("SELECT COUNT(*) FROM claude_integration_job")).isEqualTo(jobs);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void importCreatesVerifiedDraftsWithImagesAndTouchesNothingElse() throws Exception {
        Map<String, Object> publishedBefore = snapshotProject(publishedId);
        Map<String, Object> draftBefore = snapshotProject(draftId);
        Map<String, Object> builderBefore = snapshotBuilder(rahejaBuilderId);

        byte[] excel = ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(
                row("Happy Path Heights", "Concept Capital Infra", "Ghaziabad", "Uttar Pradesh", "HP-RERA-1"),
                row("Happy Path Gardens", "Raheja Developers", "Gurugram", "Haryana", "HP-RERA-2")));
        byte[] zip = imagesZipFor("Happy Path Heights", "Happy Path Gardens");

        MvcResult r = mvc.perform(upload("/properties/import", excel, zip).param("batchName", "IT batch")
                .header("Authorization", bearer(integrationToken))).andExpect(status().isAccepted()).andReturn();
        String jobId = objectMapper.readTree(r.getResponse().getContentAsString()).get("jobId").asText();
        ClaudeJobResponse job = awaitJob(jobId);
        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        assertThat(job.getSuccessfulProperties()).isEqualTo(2);

        List<ClaudePropertyResult> results = jobService.results(jobId);
        assertThat(results).hasSize(2);
        for (ClaudePropertyResult pr : results) {
            assertThat(pr.getImportStatus()).isEqualTo("CREATED_DRAFT");
            assertThat(pr.getDraft()).isTrue();
            assertThat(pr.isVerified()).isTrue();
            assertThat(pr.getErrors()).isEmpty();
            assertThat(pr.getImagesExpected()).isEqualTo(5);
            assertThat(pr.getImagesImported()).isEqualTo(5);
            int id = pr.getProjectId();

            // Draft at insertion: the trigger saw status=0 on INSERT and never status=1 afterwards.
            assertThat(jdbc.queryForList("SELECT status_value FROM claude_it_project_audit WHERE project_id = ? AND op = 'INSERT'",
                    Integer.class, id)).containsExactly(0);
            assertThat(count("SELECT COUNT(*) FROM claude_it_project_audit WHERE project_id = ? AND status_value <> 0", id)).isZero();
            assertThat(jdbc.queryForObject("SELECT status + 0 FROM projects WHERE id = ?", Integer.class, id)).isZero();

            assertThat(count("SELECT COUNT(*) FROM project_faqs WHERE project_id = ?", id)).isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM floor_plans WHERE project_id = ?", id)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM location_benefits WHERE project_id = ?", id)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM project_amenities WHERE project_id = ?", id)).isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM project_walkthrough WHERE project_Id = ?", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM project_gallery WHERE project_id = ?", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM project_desktop_banners WHERE project_id = ?", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM project_mobile_banners WHERE project_id = ?", id)).isEqualTo(1);
            Map<String, Object> p = jdbc.queryForMap("SELECT * FROM projects WHERE id = ?", id);
            assertThat(p.get("created_by_user_id")).isEqualTo(integrationUserId);
            assertThat(p.get("project_logo")).isNotNull();
            assertThat(p.get("project_thumbnail")).isNotNull();
            assertThat(p.get("state_id")).isNotNull();
            assertThat(p.get("ivr_no")).isEqualTo("8920024793");
            assertThat(p.get("project_price")).isEqualTo("0.75");
            Path folder = Paths.get("target/claude-it-uploads/properties/" + pr.getSlug());
            assertThat(Files.list(folder).count()).isEqualTo(5);

            // Not public: the published-only endpoint does not return it; the admin GET does.
            mvc.perform(get("/api/v1/projects/get/" + pr.getSlug())).andExpect(r2 -> assertThat(r2.getResponse().getStatus()).isNotEqualTo(200));
            mvc.perform(get("/api/v1/projects/admin/get-by-id/" + id).header("Authorization", bearer(integrationToken)))
                    .andExpect(status().isOk());
        }
        // New builder created with its description; existing builder untouched.
        ClaudePropertyResult heights = results.get(0);
        assertThat(heights.isBuilderCreated()).isTrue();
        assertThat(jdbc.queryForObject("SELECT builder_desc FROM builders WHERE id = ?", String.class, heights.getBuilderId()))
                .isEqualTo("Concept Capital Infra is the developer of Happy Path Heights.");
        ClaudePropertyResult gardens = results.get(1);
        assertThat(gardens.isBuilderCreated()).isFalse();
        assertThat(gardens.getBuilderId()).isEqualTo(rahejaBuilderId);
        assertThat(gardens.getUnappliedFields()).containsKey("BUILDER DESCRIPTION");
        assertThat(snapshotBuilder(rahejaBuilderId)).isEqualTo(builderBefore);
        assertThat(snapshotProject(publishedId)).isEqualTo(publishedBefore);
        assertThat(snapshotProject(draftId)).isEqualTo(draftBefore);

        // Same files again: refused as a duplicate batch, nothing new created.
        int projects = count("SELECT COUNT(*) FROM projects");
        MvcResult again = mvc.perform(upload("/properties/import", excel, zip).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString()).get("jobId").asText()).isEqualTo(jobId);
        // Same projects with edited content: every row is now a duplicate for review.
        byte[] edited = ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(
                row("Happy Path Heights", "Concept Capital Infra", "Ghaziabad", "Uttar Pradesh", "HP-RERA-1-NEW")));
        MvcResult dup = mvc.perform(upload("/properties/import", edited, null).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode dupBody = objectMapper.readTree(dup.getResponse().getContentAsString());
        assertThat(dupBody.get("status").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(dupBody.get("validation").get("rows").get(0).get("status").asText()).isEqualTo("DUPLICATE_REVIEW_REQUIRED");
        assertThat(count("SELECT COUNT(*) FROM projects")).isEqualTo(projects);
    }

    // ------------------------------------------------------------------ duplicates and partial completion

    @Test
    void duplicatesAreNeverWrittenAndPartialBatchesComplete() throws Exception {
        Map<String, Object> publishedBefore = snapshotProject(publishedId);
        Map<String, Object> draftBefore = snapshotProject(draftId);
        Map<String, Object> customBefore = snapshotProject(customSlugId);
        List<Map<String, Object>> rows = List.of(
                row("Existing Published Tower", "Raheja Developers", "Gurugram", "Haryana", "NEW-RERA-A"),
                row("Fresh Name Villas", "Raheja Developers", "Gurugram", "Haryana", "DRAFT-RERA-2"),
                row("Gamma Residency", "Raheja Developers", "Gurugram", "Haryana", "NEW-RERA-C"),
                row("Partial Batch Plaza", "Raheja Developers", "Gurugram", "Haryana", "NEW-RERA-D"));
        byte[] excel = ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, rows);
        int projects = count("SELECT COUNT(*) FROM projects");

        // Default (allowPartial=false): refused, nothing written.
        mvc.perform(upload("/properties/import", excel, null).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("SELECT COUNT(*) FROM projects")).isEqualTo(projects);

        MvcResult r = mvc.perform(upload("/properties/import", excel, null).param("allowPartial", "true")
                .header("Authorization", bearer(integrationToken))).andExpect(status().isAccepted()).andReturn();
        String jobId = objectMapper.readTree(r.getResponse().getContentAsString()).get("jobId").asText();
        ClaudeJobResponse job = awaitJob(jobId);
        assertThat(job.getStatus()).isEqualTo("PARTIALLY_COMPLETED");
        List<ClaudePropertyResult> results = jobService.results(jobId);
        assertThat(results.get(0).getImportStatus()).isEqualTo("DUPLICATE_REVIEW_REQUIRED");
        assertThat(results.get(0).getExistingProjectId()).isEqualTo(publishedId);
        assertThat(results.get(1).getImportStatus()).isEqualTo("DUPLICATE_REVIEW_REQUIRED");
        assertThat(results.get(1).getExistingProjectId()).isEqualTo(draftId);
        assertThat(results.get(2).getImportStatus()).isEqualTo("DUPLICATE_REVIEW_REQUIRED");
        assertThat(results.get(2).getExistingProjectId()).isEqualTo(customSlugId);
        assertThat(results.get(3).getImportStatus()).isEqualTo("CREATED_DRAFT");
        assertThat(count("SELECT COUNT(*) FROM projects")).isEqualTo(projects + 1);
        assertThat(snapshotProject(publishedId)).isEqualTo(publishedBefore);
        assertThat(snapshotProject(draftId)).isEqualTo(draftBefore);
        assertThat(snapshotProject(customSlugId)).isEqualTo(customBefore);
    }

    @Test
    void unknownMasterDataBlocksAndCreatesNothing() throws Exception {
        int builders = count("SELECT COUNT(*) FROM builders");
        int cities = count("SELECT COUNT(*) FROM cities");
        Map<String, Object> bad = row("Nowhere Towers", "Never Created Builders", "Atlantis", "Haryana", "NW-1");
        bad.put("PROJECT Amenities", "Gym_Helipad");
        MvcResult r = mvc.perform(upload("/properties/import", ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(bad)), null)
                .header("Authorization", bearer(integrationToken))).andExpect(status().isUnprocessableEntity()).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("UNKNOWN_CITY").contains("UNKNOWN_AMENITY");
        assertThat(count("SELECT COUNT(*) FROM builders")).isEqualTo(builders);
        assertThat(count("SELECT COUNT(*) FROM cities")).isEqualTo(cities);
        assertThat(count("SELECT COUNT(*) FROM projects WHERE slugurl = 'nowhere-towers'")).isZero();
    }

    @Test
    void invalidZipContentIsRefused() throws Exception {
        byte[] excel = ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS,
                List.of(row("Zip Check Court", "Raheja Developers", "Gurugram", "Haryana", "ZIP-1")));
        Path dir = Files.createTempDirectory("claude-it-badzip");
        byte[] jpg = ClaudeTestFixtures.image("jpg", 800, 800);
        byte[] corrupt = Files.readAllBytes(ClaudeTestFixtures.zip(dir, "c.zip",
                Map.of("Zip-Check-Court_Gallery-1_My-Property-Fact.jpg", Arrays.copyOf(jpg, jpg.length / 2))));
        mvc.perform(upload("/properties/import", excel, corrupt).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isUnprocessableEntity());
        byte[] slip = Files.readAllBytes(ClaudeTestFixtures.zip(dir, "s.zip",
                Map.of("../../Zip-Check-Court_Gallery-1_My-Property-Fact.jpg", jpg)));
        mvc.perform(upload("/properties/import", excel, slip).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(upload("/properties/import", excel, "not a zip".getBytes()).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM projects WHERE slugurl = 'zip-check-court'")).isZero();
    }

    // ------------------------------------------------------------------ importer guarantees (direct)

    private ClaudeRowPlan plan(Map<String, Object> row) throws Exception {
        ClaudeBatchValidator.Result res = validator.validate(
                ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS, List.of(row)), "t.xlsx", null, null);
        return res.plans.get(0);
    }

    @Test
    void failedRowRollsBackAndNeverModifiesExistingProjects() throws Exception {
        Map<String, Object> publishedBefore = snapshotProject(publishedId);
        int builders = count("SELECT COUNT(*) FROM builders");

        // A plan that collides with the published project (as if it appeared after validation).
        ClaudeRowPlan collide = plan(row("Rollback Probe One", "Raheja Developers", "Gurugram", "Haryana", "RB-1"));
        collide.slug = "existing-published-tower";
        assertThatThrownBy(() -> importer.createDraft(collide, integrationUserId, "it-job-collide"))
                .isInstanceOf(ClaudeDuplicateException.class);
        assertThat(count("SELECT COUNT(*) FROM claude_integration_project_claim WHERE job_id = 'it-job-collide'")).isZero();

        // A plan that fails at the database (missing city) after creating a new builder: everything rolls back.
        ClaudeRowPlan broken = plan(row("Rollback Probe Two", "Rolled Back Builders", "Gurugram", "Haryana", "RB-2"));
        broken.cityId = 99_999_999;
        assertThatThrownBy(() -> importer.createDraft(broken, integrationUserId, "it-job-broken")).isInstanceOf(Exception.class);
        assertThat(count("SELECT COUNT(*) FROM builders")).isEqualTo(builders);
        assertThat(count("SELECT COUNT(*) FROM projects WHERE slugurl = 'rollback-probe-two'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM claude_integration_project_claim WHERE job_id = 'it-job-broken'")).isZero();
        assertThat(snapshotProject(publishedId)).isEqualTo(publishedBefore);
    }

    @Test
    void concurrentImportsOfTheSameProjectCreateExactlyOne() throws Exception {
        ClaudeRowPlan p = plan(row("Concurrent Crest", "Raheja Developers", "Gurugram", "Haryana", "CC-1"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String job = "it-concurrent-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return importer.createDraft(p, integrationUserId, job);
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Object> f : futures) {
            if (f.get(60, TimeUnit.SECONDS) instanceof ClaudeDraftProjectImporter.CreatedDraft) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM projects WHERE slugurl = 'concurrent-crest'")).isEqualTo(1);
    }

    @Test
    void imagesCannotBeAttachedToAProjectThisJobDidNotCreate() {
        int before = count("SELECT COUNT(*) FROM project_gallery WHERE project_id = ?", publishedId);
        assertThatThrownBy(() -> imageRowWriter.insert("it-foreign-job", publishedId, "existing-published-tower",
                List.of(new ClaudeImageRowWriter.StoredImage(ImageKind.GALLERY, "x.jpg", "alt"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM project_gallery WHERE project_id = ?", publishedId)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ job failure and restart

    @Test
    void jobWhoseFilesVanishFailsCleanlyAndCanBeRetried() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        claudeExecutor.execute(() -> {
            try {
                hold.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        byte[] excel = ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS,
                List.of(row("Vanishing Files Vista", "Raheja Developers", "Gurugram", "Haryana", "VF-1")));
        MvcResult r = mvc.perform(upload("/properties/import", excel, null).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = objectMapper.readTree(r.getResponse().getContentAsString()).get("jobId").asText();
        storage.deleteDir(storage.jobDir(jobId));
        hold.countDown();
        ClaudeJobResponse job = awaitJob(jobId);
        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getErrorSummary()).contains("missing");
        assertThat(jobService.results(jobId).get(0).getImportStatus()).isEqualTo("FAILED");
        // The idempotency key was released, so the same batch can be retried, and this time it succeeds.
        MvcResult retry = mvc.perform(upload("/properties/import", excel, null).header("Authorization", bearer(integrationToken)))
                .andExpect(status().isAccepted()).andReturn();
        String retryId = objectMapper.readTree(retry.getResponse().getContentAsString()).get("jobId").asText();
        assertThat(retryId).isNotEqualTo(jobId);
        assertThat(awaitJob(retryId).getSuccessfulProperties()).isEqualTo(1);
    }

    @Test
    void interruptedJobsAreClosedOnRestart() {
        ClaudeIntegrationJob j = jobService.createReceived(UUID.randomUUID().toString(), "sha256:x", null, integrationUserId,
                "claude-it@example.com", "x.xlsx", null, "restart", false);
        jobService.setStatus(j.getJobId(), ClaudeJobStatus.PROCESSING);
        recovery.recover();
        ClaudeJobResponse after = jobService.response(j.getJobId());
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorSummary()).contains("restart");
    }

    // ------------------------------------------------------------------ regression: existing importer

    @Test
    void existingExcelImporterStillWorksUnchanged() throws Exception {
        byte[] legacy = ClaudeTestFixtures.workbook(List.of("PROJECT NAME", "BUILDER NAME", "CITY NAME", "STATE NAME", "META TITLE"),
                List.of(Map.of("PROJECT NAME", "Legacy Regression Tower", "BUILDER NAME", "Raheja Developers",
                        "CITY NAME", "Gurugram", "STATE NAME", "Haryana", "META TITLE", "Legacy title")));
        MvcResult r = mvc.perform(multipart("/api/v1/excel-upload/projects")
                        .file(new MockMultipartFile("file", "legacy.xlsx", "application/octet-stream", legacy))
                        .header("Authorization", bearer(staffToken)))
                .andExpect(status().isOk()).andReturn();
        Response resp = objectMapper.readValue(r.getResponse().getContentAsString(), Response.class);
        assertThat(resp.getIsSuccess()).isEqualTo(1);
        assertThat(resp.getMessage()).contains("Created: 1");
        // Existing behaviour preserved: the admin importer still publishes immediately.
        assertThat(jdbc.queryForObject("SELECT status + 0 FROM projects WHERE slugurl = 'legacy-regression-tower'", Integer.class))
                .isEqualTo(1);
        // Service-level call path is untouched too.
        Response direct = existingExcelImporter.uploadProjectsExcel(
                new MockMultipartFile("file", "legacy2.xlsx", "application/octet-stream", legacy), null);
        assertThat(direct.getMessage()).contains("Updated: 1");
    }
}
