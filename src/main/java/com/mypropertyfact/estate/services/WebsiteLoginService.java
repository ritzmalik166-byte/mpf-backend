package com.mypropertyfact.estate.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.dtos.WebsiteLoginEventDto;
import com.mypropertyfact.estate.dtos.WebsiteLoginPageResponse;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.entities.WebsiteLoginEvent;
import com.mypropertyfact.estate.repositories.WebsiteLoginEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebsiteLoginService {

    private final WebsiteLoginEventRepository repository;
    private final IpTrackService ipTrackService;
    private final ObjectMapper objectMapper;

    /**
     * Runs in its own transaction so an audit-log failure can never mark the caller's
     * login/registration transaction rollback-only (which surfaces as UnexpectedRollbackException).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User user, HttpServletRequest request, String source, String accountStatus) {
        if (user == null) {
            return;
        }
        try {
            String ip = IpTrackService.resolveClientIp(request);
            IpTrackService.GeoSnapshot geo = ipTrackService.resolveGeo(ip);

            WebsiteLoginEvent event = new WebsiteLoginEvent();
            event.setUserId(user.getId());
            event.setFullName(user.getFullName());
            event.setPhone(user.getPhone());
            event.setEmail(user.getEmail());
            event.setUserCategory(user.getUserCategory());
            event.setUserType(firstRole(user));
            event.setRoles(roleCsv(user));
            event.setVerified(user.getVerified());
            event.setEnabled(user.getEnabled());
            event.setAccountStatus(accountStatus);
            event.setRemoteAddr(truncate(ip, 64));
            if (geo != null) {
                event.setCountry(geo.country());
                event.setRegion(geo.region());
                event.setCity(geo.city());
                event.setLatitude(geo.latitude());
                event.setLongitude(geo.longitude());
                event.setOrg(geo.org());
                event.setLocationLabel(formatLocation(geo.city(), geo.region(), geo.country()));
            } else {
                event.setLocationLabel("—");
            }
            String ua = request != null ? request.getHeader("User-Agent") : null;
            event.setUserAgent(truncate(ua, 512));
            event.setSource(source != null ? source : "website-otp");
            event.setUserSnapshot(toSnapshotJson(user, ip, geo, ua, source, accountStatus));
            repository.save(event);
        } catch (Exception ex) {
            log.warn("Failed to record website login for user {}: {}", user.getId(), ex.toString());
        }
    }

    @Transactional(readOnly = true)
    public WebsiteLoginPageResponse list(HttpServletRequest request, String query, int page, int size) {
        boolean reveal = SiteTrafficService.hasTrafficRevealCookie(request);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        String q = query == null ? "" : query.trim();

        Page<WebsiteLoginEvent> raw = q.isEmpty()
                ? repository.findByOrderByLoggedInAtDesc(PageRequest.of(safePage, safeSize))
                : repository.search(q, PageRequest.of(safePage, safeSize));

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<WebsiteLoginEventDto> rows = new ArrayList<>();
        for (WebsiteLoginEvent e : raw.getContent()) {
            rows.add(toDto(e, reveal));
        }

        return WebsiteLoginPageResponse.builder()
                .content(rows)
                .totalElements(raw.getTotalElements())
                .totalPages(raw.getTotalPages())
                .number(raw.getNumber())
                .size(raw.getSize())
                .todayCount(repository.countByLoggedInAtAfter(startOfDay))
                .todayUniqueUsers(repository.countDistinctUsersSince(startOfDay))
                .allTimeCount(repository.count())
                .ipRevealActive(reveal)
                .build();
    }

    private WebsiteLoginEventDto toDto(WebsiteLoginEvent e, boolean reveal) {
        String ip = e.getRemoteAddr();
        return WebsiteLoginEventDto.builder()
                .id(e.getId())
                .loggedInAt(e.getLoggedInAt())
                .userId(e.getUserId())
                .fullName(e.getFullName())
                .phone(e.getPhone())
                .email(e.getEmail())
                .userCategory(e.getUserCategory())
                .userType(e.getUserType())
                .roles(e.getRoles())
                .verified(e.getVerified())
                .enabled(e.getEnabled())
                .accountStatus(e.getAccountStatus())
                .ip(reveal ? (ip != null && !ip.isBlank() ? ip : "—") : "Unlock to view IP")
                .country(e.getCountry())
                .region(e.getRegion())
                .city(e.getCity())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .org(e.getOrg())
                .locationLabel(e.getLocationLabel() != null ? e.getLocationLabel() : "—")
                .userAgent(e.getUserAgent())
                .source(e.getSource())
                .userSnapshot(e.getUserSnapshot())
                .ipRevealed(reveal)
                .build();
    }

    private String toSnapshotJson(
            User user,
            String ip,
            IpTrackService.GeoSnapshot geo,
            String userAgent,
            String source,
            String accountStatus) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("fullName", user.getFullName());
        map.put("phone", user.getPhone());
        map.put("email", user.getEmail());
        map.put("userCategory", user.getUserCategory());
        map.put("roles", roleCsv(user));
        map.put("verified", user.getVerified());
        map.put("enabled", user.getEnabled());
        map.put("location", user.getLocation());
        map.put("avatar", user.getAvatar());
        map.put("experience", user.getExperience());
        map.put("accountStatus", accountStatus);
        map.put("source", source);
        map.put("ip", ip);
        map.put("userAgent", userAgent);
        if (geo != null) {
            map.put("city", geo.city());
            map.put("region", geo.region());
            map.put("country", geo.country());
            map.put("org", geo.org());
            map.put("latitude", geo.latitude());
            map.put("longitude", geo.longitude());
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String firstRole(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            return "USER";
        }
        return user.getRoles().stream()
                .filter(r -> r != null && r.getRoleName() != null)
                .map(r -> r.getRoleName())
                .findFirst()
                .orElse("USER");
    }

    private static String roleCsv(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            return "USER";
        }
        return user.getRoles().stream()
                .filter(r -> r != null && r.getRoleName() != null)
                .map(r -> r.getRoleName())
                .collect(Collectors.joining(", "));
    }

    private static String formatLocation(String city, String region, String country) {
        List<String> parts = new ArrayList<>(3);
        if (city != null && !city.isBlank()) {
            parts.add(city);
        }
        if (region != null && !region.isBlank() && !region.equalsIgnoreCase(city)) {
            parts.add(region);
        }
        if (country != null && !country.isBlank()) {
            parts.add(country);
        }
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
