package com.mypropertyfact.estate.automation.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-demand, read-only triggers for the discovery module (instead of temporarily editing the cron).
 * Under /api/v1/admin/** (ADMIN / SUPERADMIN) and MANAGE_PROJECTS. Answers 404 unless mpf.discovery.enabled=true.
 *
 * <pre>
 * GET  /api/v1/admin/discovery/status   configuration (never the API key or engine id)
 * POST /api/v1/admin/discovery/screen   screen the configured input JSON against all projects (drafts included)
 * POST /api/v1/admin/discovery/run      search Magicbricks/99acres, write candidates JSON, then screen
 * </pre>
 * Nothing here imports, updates, deletes or publishes a project.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/discovery")
public class DiscoveryAdminController {

    private static final String CAN = "@adminPermissionService.can(authentication, 'MANAGE_PROJECTS')";

    private final boolean enabled;
    private final String city;
    private final Magicbricks99acresSearch search;
    private final DiscoveryScreeningJob screening;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DiscoveryAdminController(@Value("${mpf.discovery.enabled:false}") boolean enabled,
                                    @Value("${mpf.discovery.city:Ghaziabad}") String city,
                                    Magicbricks99acresSearch search, DiscoveryScreeningJob screening, ObjectMapper mapper) {
        this.enabled = enabled;
        this.city = city;
        this.search = search;
        this.screening = screening;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    @PreAuthorize(CAN)
    public ResponseEntity<Map<String, Object>> status() {
        if (!enabled) return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", true);
        s.put("city", city);
        s.put("searchProvider", "Google Programmable Search JSON API");
        s.put("searchConfigured", search.configured());
        s.put("screeningInputExists", Files.isRegularFile(screening.inputPath()));
        s.put("screeningInputFile", screening.inputPath().getFileName().toString());
        s.put("screeningOutputFile", screening.outputPath().getFileName().toString());
        s.put("running", running.get());
        return ResponseEntity.ok(s);
    }

    @PostMapping("/screen")
    @PreAuthorize(CAN)
    public ResponseEntity<Map<String, Object>> screen() {
        if (!enabled) return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
        return guarded(screening::screen);
    }

    @PostMapping("/run")
    @PreAuthorize(CAN)
    public ResponseEntity<Map<String, Object>> run() {
        if (!enabled) return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
        if (!search.configured()) {
            // Same fail-closed behaviour as the scheduled runner: no credentials, no discovery, no stale input.
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_NOT_CONFIGURED",
                    "Search API credentials missing (MPF_DISCOVERY_GOOGLE_KEY / MPF_DISCOVERY_GOOGLE_CX); no discovery was run.");
        }
        return guarded(() -> {
            List<DiscoveryCandidate> leads = search.discover(city);
            var file = screening.inputPath().toAbsolutePath();
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), leads);
            Map<String, Object> report = new LinkedHashMap<>(screening.screen());
            report.put("discoveredCandidates", leads.size());
            return report;
        });
    }

    private ResponseEntity<Map<String, Object>> guarded(Callable<Map<String, Object>> work) {
        if (!running.compareAndSet(false, true)) return error(HttpStatus.CONFLICT, "BUSY", "A discovery run is in progress.");
        try {
            return ResponseEntity.ok(work.call());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Messages are written by this module (file name, limits, provider HTTP status); never the API key.
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "DISCOVERY_FAILED", e.getMessage());
        } catch (Exception e) {
            log.warn("[discovery] run failed: {}", e.getClass().getSimpleName());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "DISCOVERY_FAILED", "Discovery failed; see server logs.");
        } finally {
            running.set(false);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
