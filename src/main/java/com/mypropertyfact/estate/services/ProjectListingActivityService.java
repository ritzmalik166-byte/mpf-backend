package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.dtos.ProjectListingActivityDto;
import com.mypropertyfact.estate.dtos.ProjectListingAverageResponse;
import com.mypropertyfact.estate.dtos.ProjectListingAverageRowDto;
import com.mypropertyfact.estate.dtos.ProjectListingAverageSummaryDto;
import com.mypropertyfact.estate.dtos.ProjectListingDetailDto;
import com.mypropertyfact.estate.dtos.ProjectListingFilterOptionsDto;
import com.mypropertyfact.estate.dtos.ProjectListingPageResponse;
import com.mypropertyfact.estate.dtos.ProjectListingRowDto;
import com.mypropertyfact.estate.dtos.ProjectListingSummaryDto;
import com.mypropertyfact.estate.entities.City;
import com.mypropertyfact.estate.entities.ListingActivityEvent;
import com.mypropertyfact.estate.entities.MasterRole;
import com.mypropertyfact.estate.entities.Project;
import com.mypropertyfact.estate.entities.PropertyListing;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.enums.ProjectApprovalStatus;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.ListingActivityEventRepository;
import com.mypropertyfact.estate.repositories.ProjectRepository;
import com.mypropertyfact.estate.repositories.PropertyListingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProjectListingActivityService {

    private static final int MAX_FETCH = 5000;
    private static final int DEFAULT_AVERAGE_DAYS = 30;
    private static final double AVG_DAYS_PER_MONTH = 30.4375;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
    private static final DateTimeFormatter DATE_LONG_FMT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("hh:mm a", Locale.UK);

    private final ProjectRepository projectRepository;
    private final PropertyListingRepository propertyListingRepository;
    private final ListingActivityEventRepository listingActivityEventRepository;
    private final ProjectService projectService;
    private final PropertyListingService propertyListingService;
    private final UserRoleService userRoleService;

    @Transactional(readOnly = true)
    public ProjectListingPageResponse search(
            String date,
            String dateFrom,
            String dateTo,
            String q,
            String projectName,
            String userName,
            String email,
            String projectId,
            String listingType,
            String status,
            String developer,
            String location,
            String userRole,
            int page,
            int size) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate selected = parseDateOr(date, today);

        boolean rangeOverride = hasText(dateFrom) || hasText(dateTo);
        LocalDate rangeStart = hasText(dateFrom) ? parseDateOr(dateFrom, selected) : selected;
        LocalDate rangeEnd = hasText(dateTo) ? parseDateOr(dateTo, selected) : selected;
        if (rangeEnd.isBefore(rangeStart)) {
            LocalDate swap = rangeStart;
            rangeStart = rangeEnd;
            rangeEnd = swap;
        }

        List<ProjectListingRowDto> rows = collectRows(rangeStart, rangeEnd, listingType);
        boolean truncated = rows.size() >= MAX_FETCH;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, MAX_FETCH));
        }

        List<ProjectListingRowDto> filtered = rows.stream()
                .filter(row -> matchesFilters(
                        row, q, projectName, userName, email, projectId, status, developer, location, userRole))
                .sorted(Comparator
                        .comparing(ProjectListingRowDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(row -> row.projectName() == null ? "" : row.projectName(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();

        ProjectListingSummaryDto summary = summarize(filtered);

        int safeSize = size == 50 || size == 100 ? size : 20;
        int safePage = Math.max(page, 0);
        int total = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
        if (safePage > totalPages - 1) {
            safePage = Math.max(0, totalPages - 1);
        }
        int fromIdx = Math.min(safePage * safeSize, total);
        int toIdx = Math.min(fromIdx + safeSize, total);
        List<ProjectListingRowDto> pageRows = filtered.subList(fromIdx, toIdx);

        String relative = relativeLabel(selected, today);
        String dateLabel = relative + " — " + selected.format(DATE_LONG_FMT);

        return new ProjectListingPageResponse(
                selected.toString(),
                dateLabel,
                relative,
                zone.getId(),
                rangeOverride,
                truncated,
                summary,
                pageRows,
                total,
                totalPages,
                safePage,
                safeSize,
                LocalDateTime.now(zone));
    }

    /**
     * Per-person listing averages for a date range (defaults to the last 30 days, inclusive of today).
     * Averages are spread over every calendar day in the range, not only the days a person listed.
     */
    @Transactional(readOnly = true)
    public ProjectListingAverageResponse averages(
            String dateFrom,
            String dateTo,
            String listingType,
            String userRole,
            String q) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate rangeEnd = parseDateOr(dateTo, today);
        LocalDate rangeStart = parseDateOr(dateFrom, rangeEnd.minusDays(DEFAULT_AVERAGE_DAYS - 1));
        if (rangeEnd.isBefore(rangeStart)) {
            LocalDate swap = rangeStart;
            rangeStart = rangeEnd;
            rangeEnd = swap;
        }
        long days = ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1;

        List<ProjectListingRowDto> rows = collectRows(rangeStart, rangeEnd, listingType);
        boolean truncated = rows.size() >= MAX_FETCH;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, MAX_FETCH));
        }

        Map<String, List<ProjectListingRowDto>> byPerson = new LinkedHashMap<>();
        for (ProjectListingRowDto row : rows) {
            if (hasText(userRole) && !"ALL".equalsIgnoreCase(userRole) && !roleMatches(row.userRole(), userRole)) {
                continue;
            }
            byPerson.computeIfAbsent(personKey(row), k -> new ArrayList<>()).add(row);
        }

        long grandTotal = byPerson.values().stream().mapToLong(List::size).sum();
        List<ProjectListingAverageRowDto> people = new ArrayList<>();
        for (Map.Entry<String, List<ProjectListingRowDto>> entry : byPerson.entrySet()) {
            ProjectListingAverageRowDto person = toAverageRow(entry.getKey(), entry.getValue(), days, grandTotal);
            if (hasText(q)
                    && !contains(person.name(), q)
                    && !contains(person.email(), q)
                    && !contains(person.userRole(), q)) {
                continue;
            }
            people.add(person);
        }
        people.sort(Comparator
                .comparingLong(ProjectListingAverageRowDto::totalListings).reversed()
                .thenComparing(p -> p.name() == null ? "" : p.name(), String.CASE_INSENSITIVE_ORDER));

        long total = people.stream().mapToLong(ProjectListingAverageRowDto::totalListings).sum();
        long contributors = people.size();
        ProjectListingAverageRowDto top = people.isEmpty() ? null : people.get(0);
        ProjectListingAverageSummaryDto summary = new ProjectListingAverageSummaryDto(
                total,
                contributors,
                round2(contributors == 0 ? 0 : total / (double) contributors),
                round2(total / (double) days),
                round2(contributors == 0 ? 0 : total / (double) contributors / days),
                top != null ? top.name() : null,
                top != null ? top.totalListings() : 0);

        return new ProjectListingAverageResponse(
                rangeStart.toString(),
                rangeEnd.toString(),
                days,
                zone.getId(),
                truncated,
                summary,
                people,
                LocalDateTime.now(zone));
    }

    private List<ProjectListingRowDto> collectRows(LocalDate rangeStart, LocalDate rangeEnd, String listingType) {
        LocalDateTime from = rangeStart.atStartOfDay();
        LocalDateTime to = rangeEnd.plusDays(1).atStartOfDay();

        List<ProjectListingRowDto> rows = new ArrayList<>();
        String type = blankToNull(listingType);
        if (type == null || "ALL".equalsIgnoreCase(type) || "NORMAL".equalsIgnoreCase(type)
                || "PROJECT".equalsIgnoreCase(type)) {
            for (Project project : projectRepository.findListedBetween(from, to)) {
                rows.add(toProjectRow(project));
            }
        }
        if (type == null || "ALL".equalsIgnoreCase(type) || "PORTAL".equalsIgnoreCase(type)
                || "BROKER".equalsIgnoreCase(type) || "PORTAL_BROKER".equalsIgnoreCase(type)) {
            for (PropertyListing listing : propertyListingRepository.findListedBetween(from, to)) {
                rows.add(toPortalRow(listing));
            }
        }
        return rows;
    }

    private static String personKey(ProjectListingRowDto row) {
        if (row.userId() != null) {
            return "U:" + row.userId();
        }
        if (hasText(row.userEmail())) {
            return "E:" + row.userEmail().trim().toLowerCase(Locale.ROOT);
        }
        return "N:" + firstNonBlank(row.listedBy(), "Unknown").toLowerCase(Locale.ROOT);
    }

    private ProjectListingAverageRowDto toAverageRow(
            String key,
            List<ProjectListingRowDto> listings,
            long days,
            long grandTotal) {
        long normal = 0;
        long portal = 0;
        long active = 0;
        long pending = 0;
        long rejected = 0;
        Set<LocalDate> activeDays = new HashSet<>();
        LocalDateTime first = null;
        LocalDateTime last = null;
        String name = null;
        String email = null;
        String role = null;
        Integer userId = null;
        for (ProjectListingRowDto row : listings) {
            if ("PROJECT".equals(row.source())) {
                normal += 1;
            } else {
                portal += 1;
            }
            String status = row.statusKey() == null ? "" : row.statusKey();
            if ("ACTIVE".equals(status) || "SOLD".equals(status)) {
                active += 1;
            } else if ("PENDING".equals(status)) {
                pending += 1;
            } else if ("REJECTED".equals(status)) {
                rejected += 1;
            }
            LocalDateTime at = row.createdAt();
            if (at != null) {
                activeDays.add(at.toLocalDate());
                if (first == null || at.isBefore(first)) {
                    first = at;
                }
                if (last == null || at.isAfter(last)) {
                    last = at;
                }
            }
            name = firstNonBlank(name, row.listedBy());
            email = firstNonBlank(email, row.userEmail());
            role = firstNonBlank(role, row.userRole());
            userId = userId != null ? userId : row.userId();
        }
        long total = listings.size();
        return new ProjectListingAverageRowDto(
                key,
                userId,
                firstNonBlank(name, email, "Unknown"),
                email,
                role,
                total,
                normal,
                portal,
                active,
                pending,
                rejected,
                activeDays.size(),
                round2(total / (double) days),
                round2(activeDays.isEmpty() ? 0 : total / (double) activeDays.size()),
                round2(total / (days / 7.0)),
                round2(total / (days / AVG_DAYS_PER_MONTH)),
                round2(grandTotal == 0 ? 0 : total * 100.0 / grandTotal),
                first,
                last,
                formatDate(first),
                formatDate(last));
    }

    private static double round2(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    @Transactional(readOnly = true)
    public ProjectListingDetailDto details(String source, Long id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing id");
        }
        String src = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if ("PROJECT".equals(src) || "NORMAL".equals(src)) {
            Project project = projectRepository.findWithListingDetailsById(id.intValue())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
            return toProjectDetail(project);
        }
        if ("PORTAL".equals(src) || "BROKER".equals(src)) {
            PropertyListing listing = propertyListingRepository.findWithListingDetailsById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
            return toPortalDetail(listing);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown listing source");
    }

    @Transactional
    public void delete(String source, Long id, User actor) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing id");
        }
        String src = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if ("PROJECT".equals(src) || "NORMAL".equals(src)) {
            Response response = projectService.deleteProject(id.intValue());
            if (response == null || response.getIsSuccess() != 1) {
                String message = response != null ? response.getMessage() : "Could not delete project";
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
            }
            return;
        }
        if ("PORTAL".equals(src) || "BROKER".equals(src)) {
            propertyListingService.deletePropertyListing(id, actor);
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown listing source");
    }

    @Transactional(readOnly = true)
    public ProjectListingFilterOptionsDto filterOptions() {
        Set<String> developers = new LinkedHashSet<>();
        developers.addAll(projectRepository.findDistinctBuilderNames());
        developers.addAll(propertyListingRepository.findDistinctBuilderNames());

        Set<String> locations = new LinkedHashSet<>();
        locations.addAll(projectRepository.findDistinctCityNames());
        locations.addAll(propertyListingRepository.findDistinctCityNames());

        List<String> statuses = List.of("ACTIVE", "PENDING", "DRAFT", "REJECTED", "UNPUBLISHED", "SOLD");
        List<String> roles = List.of("SUPERADMIN", "ADMIN", "BROKER", "OWNER", "USER");

        return new ProjectListingFilterOptionsDto(
                developers.stream().filter(ProjectListingActivityService::hasText).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                locations.stream().filter(ProjectListingActivityService::hasText).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                statuses,
                roles);
    }

    private boolean matchesFilters(
            ProjectListingRowDto row,
            String q,
            String projectName,
            String userName,
            String email,
            String projectId,
            String status,
            String developer,
            String location,
            String userRole) {
        if (hasText(projectName) && !contains(row.projectName(), projectName)) {
            return false;
        }
        if (hasText(userName) && !contains(row.listedBy(), userName)) {
            return false;
        }
        if (hasText(email) && !contains(row.userEmail(), email)) {
            return false;
        }
        if (hasText(projectId)
                && !contains(row.displayId(), projectId)
                && !contains(String.valueOf(row.id()), projectId)) {
            return false;
        }
        if (hasText(status) && !"ALL".equalsIgnoreCase(status)
                && !status.equalsIgnoreCase(row.statusKey())) {
            return false;
        }
        if (hasText(developer) && !"ALL".equalsIgnoreCase(developer) && !contains(row.developer(), developer)) {
            return false;
        }
        if (hasText(location) && !"ALL".equalsIgnoreCase(location) && !contains(row.location(), location)) {
            return false;
        }
        if (hasText(userRole) && !"ALL".equalsIgnoreCase(userRole)
                && !roleMatches(row.userRole(), userRole)) {
            return false;
        }
        if (!hasText(q)) {
            return true;
        }
        String query = q.trim();
        return contains(row.projectName(), query)
                || contains(row.listedBy(), query)
                || contains(row.userEmail(), query)
                || contains(row.displayId(), query)
                || contains(String.valueOf(row.id()), query)
                || contains(row.developer(), query)
                || contains(row.location(), query);
    }

    private static boolean roleMatches(String actual, String wanted) {
        if (!hasText(actual) || !hasText(wanted)) {
            return false;
        }
        String a = normalizeRoleKey(actual);
        String w = normalizeRoleKey(wanted);
        if (a.equals(w)) {
            return true;
        }
        if ("ADMIN".equals(w)) {
            return "ADMIN".equals(a) || "ADMINUSER".equals(a);
        }
        if ("OWNER".equals(w)) {
            return "OWNER".equals(a) || "PROPERTY_OWNER".equals(a);
        }
        return false;
    }

    private static String normalizeRoleKey(String value) {
        return String.valueOf(value)
                .replace(" ", "")
                .replace("-", "")
                .replace("/", "")
                .replace("ROLE_", "")
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private ProjectListingSummaryDto summarize(List<ProjectListingRowDto> rows) {
        long normal = 0;
        long portal = 0;
        long active = 0;
        long pending = 0;
        long rejected = 0;
        for (ProjectListingRowDto row : rows) {
            if ("PROJECT".equals(row.source())) {
                normal += 1;
            } else {
                portal += 1;
            }
            String key = row.statusKey() == null ? "" : row.statusKey();
            if ("ACTIVE".equals(key) || "SOLD".equals(key)) {
                active += 1;
            } else if ("PENDING".equals(key)) {
                pending += 1;
            } else if ("REJECTED".equals(key)) {
                rejected += 1;
            }
        }
        return new ProjectListingSummaryDto(rows.size(), normal, portal, active, pending, rejected);
    }

    private ProjectListingRowDto toProjectRow(Project project) {
        User creator = project.getCreatedBy();
        String location = joinLocation(
                project.getProjectLocality(),
                project.getCity() != null ? project.getCity().getName() : null);
        String statusName = project.getProjectStatus() != null
                ? blankToNull(project.getProjectStatus().getStatusName())
                : null;
        String statusKey = projectStatusKey(project.isStatus(), statusName);
        String statusLabel = projectStatusLabel(project.isStatus(), statusName);
        String slug = blankToNull(project.getSlugURL());
        return new ProjectListingRowDto(
                ListingActivityService.SOURCE_PROJECT,
                "NORMAL PROJECT",
                (long) project.getId(),
                String.valueOf(project.getId()),
                firstNonBlank(project.getProjectName(), "Untitled project"),
                creator != null
                        ? firstNonBlank(
                                ListingActivityService.displayName(creator),
                                creator.getEmail())
                        : null,
                creator != null ? blankToNull(creator.getEmail()) : null,
                creator != null ? displayRole(creator) : "Admin",
                creator != null ? creator.getId() : null,
                formatDate(project.getCreatedAt()),
                formatTime(project.getCreatedAt()),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                location,
                project.getBuilder() != null ? blankToNull(project.getBuilder().getBuilderName()) : null,
                statusLabel,
                statusKey,
                "/admin/dashboard/manage-projects",
                slug != null ? "/" + slug : null);
    }

    private ProjectListingRowDto toPortalRow(PropertyListing listing) {
        User user = listing.getUser();
        String listerType = user != null ? userRoleService.resolvePortalPersonaLabel(user) : "BROKER";
        City city = listing.getCity();
        String location = joinLocation(
                listing.getLocalityName(),
                city != null ? city.getName() : null);
        String displayId = firstNonBlank(listing.getPropertyUid(), String.valueOf(listing.getId()));
        return new ProjectListingRowDto(
                ListingActivityService.SOURCE_PORTAL,
                "PORTAL / BROKER",
                listing.getId(),
                displayId,
                firstNonBlank(listing.getProjectName(), listing.getTitle(), "Untitled listing"),
                user != null ? firstNonBlank(user.getFullName(), user.getEmail(), "Portal user") : "Portal user",
                user != null ? blankToNull(user.getEmail()) : blankToNull(listing.getContactEmail()),
                user != null ? displayRole(user) : displayPortalRole(listerType),
                user != null ? user.getId() : null,
                formatDate(listing.getCreatedAt()),
                formatTime(listing.getCreatedAt()),
                listing.getCreatedAt(),
                listing.getUpdatedAt(),
                location,
                firstNonBlank(
                        listing.getBuilderName(),
                        listing.getBuilder() != null ? listing.getBuilder().getBuilderName() : null),
                portalStatusLabel(listing.getApprovalStatus()),
                portalStatusKey(listing.getApprovalStatus()),
                "/admin/dashboard/property-approvals/" + listing.getId(),
                null);
    }

    private ProjectListingDetailDto toProjectDetail(Project project) {
        ProjectListingRowDto row = toProjectRow(project);
        User creator = project.getCreatedBy();
        List<ProjectListingActivityDto> activity = loadOrSynthesizeActivity(
                ListingActivityService.SOURCE_PROJECT,
                (long) project.getId(),
                row,
                project.isStatus(),
                null);
        return new ProjectListingDetailDto(
                row.source(),
                row.listingTypeLabel(),
                row.id(),
                row.displayId(),
                row.projectName(),
                row.developer(),
                row.location(),
                row.status(),
                row.statusKey(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                formatDate(project.getCreatedAt()),
                formatTime(project.getCreatedAt()),
                formatDate(project.getUpdatedAt()),
                formatTime(project.getUpdatedAt()),
                row.userId(),
                row.listedBy(),
                row.userEmail(),
                creator != null ? blankToNull(creator.getPhone()) : null,
                row.userRole(),
                creator != null ? blankToNull(creator.getAvatar()) : null,
                row.adminPath(),
                row.publicPath(),
                activity);
    }

    private ProjectListingDetailDto toPortalDetail(PropertyListing listing) {
        ProjectListingRowDto row = toPortalRow(listing);
        User user = listing.getUser();
        List<ProjectListingActivityDto> activity = loadOrSynthesizeActivity(
                ListingActivityService.SOURCE_PORTAL,
                listing.getId(),
                row,
                listing.getApprovalStatus() == ProjectApprovalStatus.APPROVED,
                listing);
        return new ProjectListingDetailDto(
                row.source(),
                row.listingTypeLabel(),
                row.id(),
                row.displayId(),
                row.projectName(),
                row.developer(),
                row.location(),
                row.status(),
                row.statusKey(),
                listing.getCreatedAt(),
                listing.getUpdatedAt(),
                formatDate(listing.getCreatedAt()),
                formatTime(listing.getCreatedAt()),
                formatDate(listing.getUpdatedAt()),
                formatTime(listing.getUpdatedAt()),
                row.userId(),
                row.listedBy(),
                row.userEmail(),
                user != null
                        ? firstNonBlank(user.getPhone(), listing.getContactPhone())
                        : blankToNull(listing.getContactPhone()),
                row.userRole(),
                user != null ? blankToNull(user.getAvatar()) : null,
                row.adminPath(),
                row.publicPath(),
                activity);
    }

    private List<ProjectListingActivityDto> loadOrSynthesizeActivity(
            String source,
            Long entityId,
            ProjectListingRowDto row,
            boolean published,
            PropertyListing listing) {
        List<ListingActivityEvent> stored =
                listingActivityEventRepository.findBySourceAndEntityIdOrderByOccurredAtAsc(source, entityId);
        List<ProjectListingActivityDto> out = new ArrayList<>();
        for (ListingActivityEvent event : stored) {
            out.add(toActivityDto(event));
        }
        if (!out.isEmpty()) {
            out.sort(Comparator.comparing(
                    ProjectListingActivityDto::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())));
            return out;
        }

        String creatorName = row.listedBy();
        Integer creatorId = row.userId();
        if (row.createdAt() != null) {
            out.add(new ProjectListingActivityDto(
                    ListingActivityService.ACTION_CREATED,
                    "Project created",
                    creatorName,
                    creatorId,
                    formatDate(row.createdAt()),
                    formatTime(row.createdAt()),
                    row.createdAt(),
                    null));
        }
        if (row.updatedAt() != null && row.createdAt() != null
                && row.updatedAt().isAfter(row.createdAt().plusMinutes(1))) {
            out.add(new ProjectListingActivityDto(
                    ListingActivityService.ACTION_UPDATED,
                    "Project updated",
                    creatorName,
                    creatorId,
                    formatDate(row.updatedAt()),
                    formatTime(row.updatedAt()),
                    row.updatedAt(),
                    null));
        }
        if (listing != null && listing.getApprovedAt() != null) {
            User approver = listing.getApprovedBy();
            boolean rejected = listing.getApprovalStatus() == ProjectApprovalStatus.REJECTED;
            out.add(new ProjectListingActivityDto(
                    rejected ? ListingActivityService.ACTION_REJECTED : ListingActivityService.ACTION_APPROVED,
                    rejected ? "Project rejected" : "Project approved",
                    approver != null
                            ? firstNonBlank(ListingActivityService.displayName(approver), "Unknown")
                            : "Unknown",
                    approver != null ? approver.getId() : null,
                    formatDate(listing.getApprovedAt()),
                    formatTime(listing.getApprovedAt()),
                    listing.getApprovedAt(),
                    listing.getRejectionReason()));
        } else if (published && ListingActivityService.SOURCE_PROJECT.equals(source) && row.updatedAt() != null) {
            out.add(new ProjectListingActivityDto(
                    ListingActivityService.ACTION_PUBLISHED,
                    "Project published",
                    creatorName,
                    creatorId,
                    formatDate(row.createdAt() != null ? row.createdAt() : row.updatedAt()),
                    formatTime(row.createdAt() != null ? row.createdAt() : row.updatedAt()),
                    row.createdAt() != null ? row.createdAt() : row.updatedAt(),
                    null));
        }
        out.sort(Comparator.comparing(
                ProjectListingActivityDto::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private ProjectListingActivityDto toActivityDto(ListingActivityEvent event) {
        return new ProjectListingActivityDto(
                event.getAction(),
                actionLabel(event.getAction()),
                firstNonBlank(event.getActorName(), "Unknown"),
                event.getActorUserId(),
                formatDate(event.getOccurredAt()),
                formatTime(event.getOccurredAt()),
                event.getOccurredAt(),
                event.getDetail());
    }

    private static String actionLabel(String action) {
        if (action == null) {
            return "Activity";
        }
        return switch (action.toUpperCase(Locale.ROOT)) {
            case ListingActivityService.ACTION_CREATED -> "Project created";
            case ListingActivityService.ACTION_UPDATED -> "Project updated";
            case ListingActivityService.ACTION_STATUS_CHANGED -> "Status changed";
            case ListingActivityService.ACTION_APPROVED -> "Project approved";
            case ListingActivityService.ACTION_REJECTED -> "Project rejected";
            case ListingActivityService.ACTION_PUBLISHED -> "Project published";
            case ListingActivityService.ACTION_UNPUBLISHED -> "Project unpublished";
            default -> action.replace('_', ' ');
        };
    }

    static String displayRole(User user) {
        if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
            return "User";
        }
        boolean superAdmin = false;
        boolean admin = false;
        boolean broker = false;
        boolean owner = false;
        boolean regular = false;
        for (MasterRole role : user.getRoles()) {
            if (role == null || role.getRoleName() == null) {
                continue;
            }
            String name = role.getRoleName().trim().toUpperCase(Locale.ROOT).replace("ROLE_", "");
            if ("SUPERADMIN".equals(name)) {
                superAdmin = true;
            } else if (UserRoleService.isStaffAdminRoleName(name)) {
                admin = true;
            } else if ("BROKER".equals(name)) {
                broker = true;
            } else if ("OWNER".equals(name) || "PROPERTY_OWNER".equals(name)) {
                owner = true;
            } else if ("USER".equals(name)) {
                regular = true;
            }
        }
        if (superAdmin) {
            return "Super Admin";
        }
        if (admin) {
            return "Admin";
        }
        if (broker) {
            return "Broker";
        }
        if (owner) {
            return "Owner";
        }
        if (regular) {
            return "User";
        }
        return "User";
    }

    private static String displayPortalRole(String persona) {
        if ("OWNER".equalsIgnoreCase(persona)) {
            return "Owner";
        }
        if ("BROKER".equalsIgnoreCase(persona)) {
            return "Broker";
        }
        return firstNonBlank(persona, "Broker");
    }

    private static String projectStatusKey(boolean published, String statusName) {
        if (statusName != null && statusName.toLowerCase(Locale.ROOT).contains("sold")) {
            return "SOLD";
        }
        return published ? "ACTIVE" : "UNPUBLISHED";
    }

    private static String projectStatusLabel(boolean published, String statusName) {
        if (statusName != null && statusName.toLowerCase(Locale.ROOT).contains("sold")) {
            return "Sold";
        }
        if (hasText(statusName) && published) {
            return statusName;
        }
        return published ? "Active" : "Unpublished";
    }

    private static String portalStatusKey(ProjectApprovalStatus status) {
        if (status == null) {
            return "PENDING";
        }
        return switch (status) {
            case APPROVED -> "ACTIVE";
            case PENDING, REQUIRES_CHANGES -> "PENDING";
            case DRAFT -> "DRAFT";
            case REJECTED -> "REJECTED";
        };
    }

    private static String portalStatusLabel(ProjectApprovalStatus status) {
        if (status == null) {
            return "Pending";
        }
        return switch (status) {
            case APPROVED -> "Active";
            case PENDING -> "Pending";
            case REQUIRES_CHANGES -> "Requires changes";
            case DRAFT -> "Draft";
            case REJECTED -> "Rejected";
        };
    }

    private static String relativeLabel(LocalDate selected, LocalDate today) {
        if (selected.equals(today)) {
            return "Today";
        }
        if (selected.equals(today.minusDays(1))) {
            return "Yesterday";
        }
        if (selected.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        if (selected.equals(today.plusDays(2))) {
            return "Day After Tomorrow";
        }
        return "Custom Date";
    }

    private static LocalDate parseDateOr(String raw, LocalDate fallback) {
        if (!hasText(raw)) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static String formatDate(LocalDateTime value) {
        return value == null ? null : value.format(DATE_FMT);
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? null : value.format(TIME_FMT).toUpperCase(Locale.ROOT);
    }

    private static String joinLocation(String locality, String city) {
        if (hasText(locality) && hasText(city)) {
            return locality.trim() + ", " + city.trim();
        }
        return firstNonBlank(locality, city);
    }

    private static boolean contains(String haystack, String needle) {
        if (!hasText(haystack) || !hasText(needle)) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
