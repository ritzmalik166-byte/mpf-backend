package com.mypropertyfact.estate.automation.discovery;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, conservative checks: NEVER interpret a number's presence as official RERA verification. */
public final class DiscoveryScreeningRules {
    private DiscoveryScreeningRules() { }

    /** UP RERA project registration: UPRERAPRJ + digits, optionally followed by /MM/YYYY (or other suffixes). */
    private static final Pattern UP_RERA_BASE = Pattern.compile("UPRERAPRJ\\d+");

    public static List<String> screen(DiscoveryCandidate c) {
        List<String> reasons = new ArrayList<>();
        if (c == null) return List.of("MISSING_CANDIDATE");
        if (!allowedSource(c.sourceUrl())) reasons.add("SOURCE_NOT_ALLOWED");
        if (blank(c.projectName()) || blank(c.builderName()) || blank(c.city())) reasons.add("MISSING_IDENTITY");
        // Missing status is reported as missing (search snippets never establish it); unknown values as unsupported.
        if (blank(c.projectStatus())) reasons.add("PROJECT_STATUS_MISSING");
        else if (!validStatus(c.projectStatus())) reasons.add("UNSUPPORTED_PROJECT_STATUS");
        if (blank(c.reraNumber())) reasons.add("RERA_NUMBER_MISSING");
        // Evidence URL alone is not an official-registration verification.
        reasons.add("OFFICIAL_RERA_VERIFICATION_REQUIRED");
        if (blank(c.imagePermissionEvidence())) reasons.add("IMAGE_PERMISSION_UNCONFIRMED");
        return List.copyOf(reasons);
    }

    public static boolean allowedSource(String input) {
        if (blank(input)) return false;
        try {
            URI u = URI.create(input.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null) return false;
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("magicbricks.com") || host.endsWith(".magicbricks.com")
                    || host.equals("99acres.com") || host.endsWith(".99acres.com");
        } catch (IllegalArgumentException e) { return false; }
    }

    public static boolean validStatus(String status) {
        if (blank(status)) return false;
        String normalized = status.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return Set.of("newlaunch", "newlaunched", "underconstruction", "readytomove").contains(normalized);
    }

    public static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * Comparable RERA key. The MPF database stores the same UP registration both as "UPRERAPRJ844382" and
     * "UPRERAPRJ844382/02/2026", so UP numbers compare on the base registration (UPRERAPRJ + digits).
     * Other formats fall back to the fully normalized value. Blank input gives "".
     */
    public static String reraKey(String rera) {
        if (blank(rera)) return "";
        String compact = rera.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher m = UP_RERA_BASE.matcher(compact);
        if (m.find()) return m.group();
        return normalize(rera);
    }

    /**
     * All comparable RERA keys in one field. Some MPF rows hold several registrations in one column,
     * e.g. "UPRERAPRJ2303, UPRERAPRJ2863" (phases); a match on ANY of them counts.
     */
    public static Set<String> reraKeys(String rera) {
        if (blank(rera)) return Set.of();
        String compact = rera.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = UP_RERA_BASE.matcher(compact);
        while (m.find()) keys.add(m.group());
        if (keys.isEmpty()) keys.add(normalize(rera));
        return keys;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
