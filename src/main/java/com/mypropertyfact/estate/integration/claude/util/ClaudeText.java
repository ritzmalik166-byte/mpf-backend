package com.mypropertyfact.estate.integration.claude.util;

import java.util.Locale;

/** Normalisation helpers shared by the Claude connector. Pure functions, no Spring. */
public final class ClaudeText {

    private ClaudeText() {
    }

    /**
     * Header normalisation, identical to the existing importer's {@code buildHeaderIndex}: trim,
     * upper-case, collapse Java whitespace. {@code " PROJECT CONFIGURATION"} → {@code "PROJECT CONFIGURATION"},
     * {@code "PROJECT Amenities"} → {@code "PROJECT AMENITIES"}.
     */
    public static String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace(' ', ' ').trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Master-data lookup key: lower-case, trimmed, whitespace collapsed. Records themselves are never changed. */
    public static String lookupKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace(' ', ' ').trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Amenity key. The existing importer turns {@code -} into a space before looking an amenity up;
     * this applies the same rule to both the cell token and the master title, so
     * {@code "Wi-Fi-Connectivity"} matches the master title {@code "Wi-fi Connectivity"}.
     */
    public static String amenityKey(String raw) {
        if (raw == null) {
            return "";
        }
        return lookupKey(raw.replace('-', ' ').replace('_', ' '));
    }

    /** RERA comparison key: upper-case with all whitespace removed. */
    public static String reraKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace(' ', ' ').replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Interprets a native-query boolean column (BIT(1) → byte[]/Boolean, TINYINT → Number). */
    public static boolean dbTrue(Object dbValue) {
        if (dbValue == null) {
            return false;
        }
        if (dbValue instanceof Boolean b) {
            return b;
        }
        if (dbValue instanceof Number n) {
            return n.intValue() != 0;
        }
        if (dbValue instanceof byte[] bytes) {
            return bytes.length > 0 && bytes[0] != 0;
        }
        String s = dbValue.toString().trim();
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    /** Spreadsheet column letter for a 0-based index (0 → A, 26 → AA). */
    public static String columnLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }
}
