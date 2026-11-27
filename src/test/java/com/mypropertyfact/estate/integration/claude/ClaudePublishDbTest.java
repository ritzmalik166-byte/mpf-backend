package com.mypropertyfact.estate.integration.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.entities.*;
import com.mypropertyfact.estate.integration.claude.dto.ClaudeJobResponse;
import com.mypropertyfact.estate.integration.claude.entity.ClaudeJobStatus;
import com.mypropertyfact.estate.integration.claude.service.ClaudeJobService;
import com.mypropertyfact.estate.repositories.MasterRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Publish endpoint + Sheet/MCP account separation, against a DISPOSABLE schema only ({@code mpf_claude_publish_it},
 * dropped by create-drop). Drafts are created through the real import endpoint. Never touches the real MPF database.
 *
 * <p>Run with: {@code CLAUDE_IT=true ./mvnw test -Dtest=ClaudePublishDbTest}
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_IT", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/mpf_claude_publish_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "upload_dir=target/claude-publish-it-uploads/",
        "claude.integration.enabled=true",
        "claude.integration.allowed-emails=sheet-it@example.com,mcp-it@example.com",
        "claude.integration.temp-dir=target/claude-publish-it-tmp",
        "claude.integration.rate-limit-per-minute=1000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudePublishDbTest {

    private static final String SCHEMA = "mpf_claude_publish_it";
    private static final String API = "/api/v1/integrations/claude";
    private static final String PASSWORD = "It-Passw0rd!";
    private static final String CONFIRM = "{\"confirm\": true}";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MasterRoleRepository roleRepository;
    @Autowired ClaudeJobService jobService;
    @Autowired ObjectMapper objectMapper;
    @PersistenceContext EntityManager em;

    /** Same reason as ClaudeIntegrationDbTest: this startup runner cannot complete on a fresh schema. */
    @org.springframework.boot.test.mock.mockito.MockBean
    com.mypropertyfact.estate.config.BlogStatusCheckConstraintRunner blogStatusCheckConstraintRunner;

    private String sheetToken;
    private String mcpToken;
    private String staffToken;
    private int manualDraftId;
    private int manualPublishedId;

    @BeforeAll
    void setUp() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            Assumptions.assumeTrue(SCHEMA.equals(c.getCatalog()), "Refusing to run outside the disposable schema");
        }
        tx.executeWithoutResult(s -> seed());
        jdbc.execute("DROP TABLE IF EXISTS publish_it_audit");
        jdbc.execute("CREATE TABLE publish_it_audit (id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, op VARCHAR(10), status_value INT)");
        jdbc.execute("DROP TRIGGER IF EXISTS publish_it_projects_au");
        jdbc.execute("CREATE TRIGGER publish_it_projects_au AFTER UPDATE ON projects FOR EACH ROW "
                + "INSERT INTO publish_it_audit(project_id, op, status_value) VALUES (NEW.id, 'UPDATE', NEW.status + 0)");
        sheetToken = login("sheet-it@example.com", "sheet-it");
        mcpToken = login("mcp-it@example.com", "mcp-it");
        staffToken = login("staff-it@example.com", "staff-it");
    }

    private void seed() {
        Country india = persist(new Country(), c -> c.setCountryName("India"));
        State up = persist(new State(), s -> { s.setStateName("Uttar Pradesh"); s.setCountry(india); });
        City ghaziabad = persist(new City(), c -> { c.setName("Ghaziabad"); c.setSlugUrl("ghaziabad"); c.setState(up); });
        ProjectStatus ready = persist(new ProjectStatus(), p -> p.setStatusName("Ready To Move"));
        ProjectTypes residential = persist(new ProjectTypes(), t -> { t.setProjectTypeName("Residential"); t.setSlugUrl("residential"); });
        for (String a : List.of("Swimming Pool", "Gym", "Wi-fi Connectivity", "Children's Play Area")) {
            persist(new Amenity(), x -> x.setTitle(a));
        }
        Builder builder = persist(new Builder(), b -> { b.setBuilderName("Publish Test Builder"); b.setSlugUrl("publish-test-builder"); });
        manualDraftId = project("Manual Draft Heights", "manual-draft-heights", false, builder, ghaziabad, ready, residential).getId();
        manualPublishedId = project("Manual Live Towers", "manual-live-towers", true, builder, ghaziabad, ready, residential).getId();
        MasterRole admin = roleRepository.findByRoleNameIgnoreCase("ADMIN").orElseGet(() -> persist(new MasterRole(), r -> {
            r.setRoleName("ADMIN");
            r.setIsActive(true);
        }));
        user("sheet-it@example.com", "sheet-it", admin);   // Sheet Integration account (allow-listed)
        user("mcp-it@example.com", "mcp-it", admin);       // ChatGPT/MCP gateway account (allow-listed)
        user("staff-it@example.com", "staff-it", admin);   // ordinary admin with MANAGE_PROJECTS, NOT allow-listed
    }

    // ------------------------------------------------------------------ helpers

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

    private Project project(String name, String slug, boolean published, Builder b, City c, ProjectStatus s, ProjectTypes t) {
        return persist(new Project(), p -> {
            p.setProjectName(name);
            p.setSlugURL(slug);
            p.setStatus(published);
            p.setBuilder(b);
            p.setCity(c);
            p.setProjectStatus(s);
            p.setProjectTypes(t);
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

    /** Imports one project through the real endpoint as the given account and returns the created draft id. */
    private int importDraft(String token, String projectName, String rera) throws Exception {
        byte[] excel = ClaudeTestFixtures.workbook(ClaudeTestFixtures.HEADERS,
                List.of(ClaudeTestFixtures.validRow(projectName, "Publish Test Builder", "Ghaziabad", "Uttar Pradesh", rera)));
        MvcResult r = mvc.perform(multipart(API + "/properties/import")
                        .file(new MockMultipartFile("excelFile", "p.xlsx", "application/octet-stream", excel))
                        .param("idempotencyKey", "publish-it-" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = objectMapper.readTree(r.getResponse().getContentAsString()).get("jobId").asText();
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end) {
            ClaudeJobResponse job = jobService.response(jobId);
            if (ClaudeJobStatus.valueOf(job.getStatus()).isTerminal()) {
                // NO_IMAGES warning only => verified draft
                Integer id = jobService.results(jobId).get(0).getProjectId();
                assertThat(id).as("draft created for " + projectName).isNotNull();
                assertThat(statusOf(id)).isZero();
                return id;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("import did not finish");
    }

    private ResultActions publish(Object id, String token, String body) throws Exception {
        var req = post(API + "/projects/" + id + "/publish").contentType(MediaType.APPLICATION_JSON);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.content(body);
        return mvc.perform(req);
    }

    private JsonNode json(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private int statusOf(int id) {
        return jdbc.queryForObject("SELECT status + 0 FROM projects WHERE id = ?", Integer.class, id);
    }

    private Map<String, Object> rowWithoutStatus(int id) {
        Map<String, Object> m = new TreeMap<>(jdbc.queryForMap("SELECT * FROM projects WHERE id = ?", id));
        m.remove("status");
        return m;
    }

    private int updatesOf(int id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM publish_it_audit WHERE project_id = ?", Integer.class, id);
    }

    // ------------------------------------------------------------------ account separation (Phase 1 / 2)

    @Test
    void sheetAndMcpAccountsAreAllowedAndDoNotLogEachOtherOut() throws Exception {
        String sheet = login("sheet-it@example.com", "sheet-it");
        String mcp = login("mcp-it@example.com", "mcp-it");      // MCP logs in AFTER the sheet
        mvc.perform(get(API + "/template?format=json").header("Authorization", "Bearer " + sheet)).andExpect(status().isOk());
        mvc.perform(get(API + "/template?format=json").header("Authorization", "Bearer " + mcp)).andExpect(status().isOk());
        String sheetAgain = login("sheet-it@example.com", "sheet-it");
        // Re-login of the sheet invalidates only the sheet's OLD token, never the MCP token.
        mvc.perform(get(API + "/template?format=json").header("Authorization", "Bearer " + mcp)).andExpect(status().isOk());
        mvc.perform(get(API + "/template?format=json").header("Authorization", "Bearer " + sheetAgain)).andExpect(status().isOk());
        sheetToken = sheetAgain;
    }

    @Test
    void notAllowListedAdminIsForbiddenAndAnonymousIsUnauthorized() throws Exception {
        assertThat(json(mvc.perform(get(API + "/template?format=json").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())).path("error").asText()).isEqualTo("FORBIDDEN");
        mvc.perform(get(API + "/template?format=json")).andExpect(status().isUnauthorized());
        // A malformed JWT is rejected. NOTE (pre-existing, not changed here): the shared JwtAuthenticationFilter forwards the
        // MalformedJwtException to the global handler, which answers 500 instead of 401. It is never accepted.
        assertThat(mvc.perform(get(API + "/template?format=json").header("Authorization", "Bearer not.a.jwt"))
                .andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void sheetAccountStaysBlockedFromExistingWriteApis() throws Exception {
        mvc.perform(post("/api/v1/projects/add-new").header("Authorization", "Bearer " + sheetToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/projects/delete/" + manualPublishedId).header("Authorization", "Bearer " + sheetToken))
                .andExpect(status().isForbidden());
        assertThat(statusOf(manualPublishedId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ publish (Phase 5)

    @Test
    void ownDraftIsPublishedChangingOnlyStatusAndASecondRequestIsRefused() throws Exception {
        int id = importDraft(sheetToken, "Sheet Publish Residency", "UPRERAPRJ700001");
        Map<String, Object> before = rowWithoutStatus(id);
        int updatesBefore = updatesOf(id);

        JsonNode ok = json(publish(id, sheetToken, CONFIRM).andExpect(status().isOk()));
        assertThat(ok.path("isSuccess").asInt()).isEqualTo(1);
        assertThat(ok.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(ok.path("projectId").asInt()).isEqualTo(id);
        assertThat(ok.path("slug").asText()).isEqualTo("sheet-publish-residency");

        assertThat(statusOf(id)).isEqualTo(1);
        assertThat(rowWithoutStatus(id)).isEqualTo(before);                 // every other column unchanged (incl. updated_at)
        assertThat(updatesOf(id) - updatesBefore).isEqualTo(1);              // exactly one UPDATE statement
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_activity_event WHERE entity_id = ? AND action = 'PUBLISHED'",
                Integer.class, id)).isEqualTo(1);                           // audit written after commit

        JsonNode again = json(publish(id, sheetToken, CONFIRM).andExpect(status().isConflict()));
        assertThat(again.path("error").asText()).isEqualTo("ALREADY_PUBLISHED");
        assertThat(again.path("isSuccess").asInt()).isZero();
        assertThat(updatesOf(id) - updatesBefore).isEqualTo(1);
    }

    @Test
    void draftImportedByTheMcpAccountCannotBePublishedByTheSheet() throws Exception {
        int id = importDraft(mcpToken, "Mcp Owned Enclave", "UPRERAPRJ700002");
        JsonNode r = json(publish(id, sheetToken, CONFIRM).andExpect(status().isForbidden()));
        assertThat(r.path("error").asText()).isEqualTo("NOT_CREATED_BY_THIS_ACCOUNT");
        assertThat(statusOf(id)).isZero();
        assertThat(updatesOf(id)).isZero();
    }

    @Test
    void manualProjectsAndUnknownIdsAreRefused() throws Exception {
        assertThat(json(publish(manualDraftId, sheetToken, CONFIRM).andExpect(status().isForbidden())).path("error").asText())
                .isEqualTo("NOT_CREATED_BY_THIS_ACCOUNT");
        assertThat(json(publish(manualPublishedId, sheetToken, CONFIRM).andExpect(status().isForbidden())).path("error").asText())
                .isEqualTo("NOT_CREATED_BY_THIS_ACCOUNT");
        assertThat(json(publish(999_999, sheetToken, CONFIRM).andExpect(status().isNotFound())).path("error").asText())
                .isEqualTo("PROJECT_NOT_FOUND");
        assertThat(json(publish("abc", sheetToken, CONFIRM).andExpect(status().isBadRequest())).path("error").asText())
                .isEqualTo("INVALID_PROJECT_ID");
        assertThat(statusOf(manualDraftId)).isZero();
        assertThat(updatesOf(manualDraftId) + updatesOf(manualPublishedId)).isZero();
    }

    @Test
    void publishingRequiresExplicitConfirmation() throws Exception {
        int id = importDraft(sheetToken, "Confirm Required Court", "UPRERAPRJ700003");
        assertThat(json(publish(id, sheetToken, null).andExpect(status().isBadRequest())).path("error").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(json(publish(id, sheetToken, "{\"confirm\": false}").andExpect(status().isBadRequest())).path("error").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(json(publish(id, sheetToken, "{\"confirm\": \"yes\"}").andExpect(status().isBadRequest())).path("error").asText()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(json(publish(id, sheetToken, "not json").andExpect(status().isBadRequest())).path("error").asText()).isEqualTo("INVALID_JSON");
        assertThat(statusOf(id)).isZero();
    }

    @Test
    void unauthorizedAndAnonymousCallersCannotPublish() throws Exception {
        int id = importDraft(sheetToken, "Auth Guarded Greens", "UPRERAPRJ700004");
        publish(id, null, CONFIRM).andExpect(status().isUnauthorized());
        // Malformed token: rejected (pre-existing filter behaviour answers 500, see the test above), never published.
        assertThat(publish(id, "not.a.jwt", CONFIRM).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(json(publish(id, staffToken, CONFIRM).andExpect(status().isForbidden())).path("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(statusOf(id)).isZero();
        assertThat(updatesOf(id)).isZero();
    }

    @Test
    void unverifiedImportOrSlugClashIsBlocked() throws Exception {
        int unverified = importDraft(sheetToken, "Unverified Import Plaza", "UPRERAPRJ700005");
        jdbc.update("UPDATE claude_integration_job_item SET verified = 0 WHERE project_id = ?", unverified);
        JsonNode r1 = json(publish(unverified, sheetToken, CONFIRM).andExpect(status().isUnprocessableEntity()));
        assertThat(r1.path("error").asText()).isEqualTo("PUBLISH_BLOCKED");
        assertThat(r1.path("reasons").toString()).contains("IMPORT_NOT_VERIFIED");
        assertThat(statusOf(unverified)).isZero();

        int clash = importDraft(sheetToken, "Slug Clash Arcade", "UPRERAPRJ700006");
        // A live project with the same slug appears after the import (e.g. created manually in the admin panel).
        jdbc.update("INSERT INTO projects (project_name, slugurl, status, show_featured_properties) VALUES ('Slug Clash Arcade (manual)', 'slug-clash-arcade', 1, 0)");
        JsonNode r2 = json(publish(clash, sheetToken, CONFIRM).andExpect(status().isUnprocessableEntity()));
        assertThat(r2.path("reasons").toString()).contains("SLUG_CONFLICT");
        assertThat(statusOf(clash)).isZero();
    }

    @Test
    void concurrentPublishRequestsPublishExactlyOnce() throws Exception {
        int id = importDraft(sheetToken, "Concurrent Publish Point", "UPRERAPRJ700007");
        int updatesBefore = updatesOf(id);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return publish(id, sheetToken, CONFIRM).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) codes.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(codes).containsOnlyOnce(200);
        assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(3);
        assertThat(statusOf(id)).isEqualTo(1);
        assertThat(updatesOf(id) - updatesBefore).isEqualTo(1);
    }
}
