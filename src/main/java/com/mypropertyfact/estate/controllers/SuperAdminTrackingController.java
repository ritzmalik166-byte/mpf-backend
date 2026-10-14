package com.mypropertyfact.estate.controllers;

import com.mypropertyfact.estate.dtos.AdminAuditLogEntryDto;
import com.mypropertyfact.estate.dtos.AdminAuditLogPageResponse;
import com.mypropertyfact.estate.dtos.IpTrackEventPageResponse;
import com.mypropertyfact.estate.dtos.IpTrackIpPageResponse;
import com.mypropertyfact.estate.dtos.IpTrackSummaryResponse;
import com.mypropertyfact.estate.dtos.SiteTrafficSummaryResponse;
import com.mypropertyfact.estate.dtos.SiteTrafficVisitPageResponse;
import com.mypropertyfact.estate.dtos.SuperAdminNotificationsResponse;
import com.mypropertyfact.estate.dtos.TrafficRevealRequest;
import com.mypropertyfact.estate.dtos.LiveListingsResponse;
import com.mypropertyfact.estate.dtos.PortalListingStatsResponse;
import com.mypropertyfact.estate.dtos.ProjectListingAverageResponse;
import com.mypropertyfact.estate.dtos.ProjectListingDetailDto;
import com.mypropertyfact.estate.dtos.ProjectListingFilterOptionsDto;
import com.mypropertyfact.estate.dtos.ProjectListingPageResponse;
import com.mypropertyfact.estate.dtos.WebsiteLoginPageResponse;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.services.AdminAuditLogService;
import com.mypropertyfact.estate.services.ListingActivityService;
import com.mypropertyfact.estate.services.LiveListingsService;
import com.mypropertyfact.estate.services.PortalListingStatsService;
import com.mypropertyfact.estate.services.ProjectListingActivityService;
import com.mypropertyfact.estate.services.IpTrackService;
import com.mypropertyfact.estate.services.SiteTrafficService;
import com.mypropertyfact.estate.services.SuperAdminNotificationService;
import com.mypropertyfact.estate.services.WebsiteLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/super")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERADMIN')")
public class SuperAdminTrackingController {

    private final SiteTrafficService siteTrafficService;
    private final IpTrackService ipTrackService;
    private final AdminAuditLogService adminAuditLogService;
    private final SuperAdminNotificationService superAdminNotificationService;
    private final WebsiteLoginService websiteLoginService;
    private final PortalListingStatsService portalListingStatsService;
    private final LiveListingsService liveListingsService;
    private final ProjectListingActivityService projectListingActivityService;

    @Value("${http.secure}")
    private boolean httpSecure;

    @Value("${cookies.domain:}")
    private String cookiesDomain;

    @Value("${mpf.traffic-reveal-pin:2026}")
    private String trafficRevealPin;

    @GetMapping("/traffic/summary")
    public ResponseEntity<SiteTrafficSummaryResponse> trafficSummary() {
        return ResponseEntity.ok(siteTrafficService.buildSummary());
    }

    @GetMapping("/traffic/reveal-status")
    public ResponseEntity<Map<String, Object>> trafficRevealStatus(HttpServletRequest request) {
        boolean revealed = SiteTrafficService.hasTrafficRevealCookie(request);
        return ResponseEntity.ok(Map.of(
                "revealed", revealed,
                "requiresPin", true));
    }

    @PostMapping("/traffic/reveal")
    public ResponseEntity<?> trafficReveal(
            @RequestBody(required = false) TrafficRevealRequest body,
            HttpServletResponse response) {
        String pin = body != null && body.getPin() != null ? body.getPin().trim() : "";
        String expected = trafficRevealPin != null ? trafficRevealPin.trim() : "2026";
        if (!expected.equals(pin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid_pin"));
        }
        response.addHeader("Set-Cookie", buildRevealCookie(8 * 3600).toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/traffic/visits")
    public ResponseEntity<SiteTrafficVisitPageResponse> trafficVisits(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(siteTrafficService.listRecentVisits(request, page, size));
    }

    /**
     * CSV export of public-site traffic events in the last N hours (1–168). Real IPs only if the
     * traffic-reveal cookie is set (same as on-screen table).
     */
    @GetMapping(value = "/traffic/visits-export", produces = "text/csv")
    public ResponseEntity<byte[]> trafficVisitsExport(
            HttpServletRequest request,
            @RequestParam(defaultValue = "24") int hours) {
        int safeHours = Math.min(Math.max(hours, 1), 168);
        if (!siteTrafficService.hasExportWindowHistory(safeHours)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Export is available only after " + safeHours + " hours of collected traffic data.")
                            .getBytes(StandardCharsets.UTF_8));
        }
        byte[] csv = siteTrafficService.buildVisitsCsvExport(request, safeHours);
        String filename = "mpf-public-traffic-" + safeHours + "h.csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    @GetMapping("/ip-track/summary")
    public ResponseEntity<IpTrackSummaryResponse> ipTrackSummary() {
        return ResponseEntity.ok(ipTrackService.buildSummary());
    }

    @GetMapping("/ip-track/ips")
    public ResponseEntity<IpTrackIpPageResponse> ipTrackIps(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "false") boolean scansOnly,
            @RequestParam(required = false) Integer hours) {
        return ResponseEntity.ok(
                ipTrackService.listIpSummaries(request, page, size, scansOnly, hours));
    }

    @GetMapping("/ip-track/events")
    public ResponseEntity<IpTrackEventPageResponse> ipTrackEvents(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean scansOnly,
            @RequestParam(required = false) String ip) {
        return ResponseEntity.ok(ipTrackService.listEvents(request, ip, scansOnly, page, size));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<AdminAuditLogPageResponse> auditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String pathContains) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Page<AdminAuditLogEntryDto> result = adminAuditLogService.search(
                from,
                to,
                email,
                success,
                pathContains,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt")));
        AdminAuditLogPageResponse body = AdminAuditLogPageResponse.builder()
                .content(result.getContent())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .number(result.getNumber())
                .size(result.getSize())
                .build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/website-logins")
    public ResponseEntity<WebsiteLoginPageResponse> websiteLogins(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(websiteLoginService.list(request, q, page, size));
    }

    @GetMapping("/portal-listing-stats")
    public ResponseEntity<PortalListingStatsResponse> portalListingStats() {
        return ResponseEntity.ok(portalListingStatsService.build());
    }

    @GetMapping("/live-listings")
    public ResponseEntity<LiveListingsResponse> liveListings() {
        return ResponseEntity.ok(liveListingsService.build());
    }

    @GetMapping("/project-listings")
    public ResponseEntity<ProjectListingPageResponse> projectListings(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String listingType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String developer,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String userRole,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(projectListingActivityService.search(
                date,
                dateFrom,
                dateTo,
                q,
                projectName,
                userName,
                email,
                projectId,
                listingType,
                status,
                developer,
                location,
                userRole,
                page,
                size));
    }

    @GetMapping("/project-listings/averages")
    public ResponseEntity<ProjectListingAverageResponse> projectListingAverages(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String listingType,
            @RequestParam(required = false) String userRole,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(projectListingActivityService.averages(dateFrom, dateTo, listingType, userRole, q));
    }

    @GetMapping("/project-listings/filter-options")
    public ResponseEntity<ProjectListingFilterOptionsDto> projectListingFilterOptions() {
        return ResponseEntity.ok(projectListingActivityService.filterOptions());
    }

    @GetMapping("/project-listings/{source}/{id}")
    public ResponseEntity<ProjectListingDetailDto> projectListingDetails(
            @PathVariable String source,
            @PathVariable Long id) {
        return ResponseEntity.ok(projectListingActivityService.details(source, id));
    }

    @DeleteMapping("/project-listings/{source}/{id}")
    public ResponseEntity<Map<String, Object>> deleteProjectListing(
            @PathVariable String source,
            @PathVariable Long id) {
        User actor = ListingActivityService.currentUserOrNull();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        projectListingActivityService.delete(source, id, actor);
        return ResponseEntity.ok(Map.of("success", true, "message", "Listing deleted"));
    }

    @GetMapping("/notifications")
    public ResponseEntity<SuperAdminNotificationsResponse> notifications(
            @RequestParam(required = false) String since) {
        LocalDateTime sinceDt = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceDt = LocalDateTime.parse(since.trim());
            } catch (DateTimeParseException ignored) {
                sinceDt = null;
            }
        }
        return ResponseEntity.ok(superAdminNotificationService.buildFeed(sinceDt));
    }

    /**
     * Same rules as auth cookies: omit Domain when blank for localhost cross-port dev.
     */
    private ResponseCookie buildRevealCookie(long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(
                        SiteTrafficService.TRAFFIC_REVEAL_COOKIE,
                        SiteTrafficService.TRAFFIC_REVEAL_VALUE)
                .httpOnly(true)
                .secure(httpSecure)
                .path("/")
                .sameSite(httpSecure ? "None" : "Lax")
                .maxAge(maxAgeSeconds);
        if (cookiesDomain != null && !cookiesDomain.isBlank()) {
            b = b.domain(cookiesDomain.trim());
        }
        return b.build();
    }
}
