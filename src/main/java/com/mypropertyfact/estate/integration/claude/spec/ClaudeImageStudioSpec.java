package com.mypropertyfact.estate.integration.claude.spec;

import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec.ImageKind;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Approved MPF Image Studio output sizes. An image that already has its slot's exact size is stored
 * unchanged (no rescale, no re-encode). Gallery images have no fixed size: the original is kept.
 */
public final class ClaudeImageStudioSpec {

    private ClaudeImageStudioSpec() {
    }

    /** Exact {width, height} for a kind, or {@code null} for GALLERY (original size is kept). */
    public static int[] size(ImageKind kind) {
        return switch (kind) {
            case DESKTOP -> new int[]{2225, 1065};
            case MOBILE -> new int[]{2700, 2700};
            case LOCATION -> new int[]{815, 815};
            case THUMBNAIL -> new int[]{400, 400};
            case LOGO -> new int[]{390, 100};
            case GALLERY -> null;
        };
    }

    /** True when the image is stored as its original: every gallery image, or an exact spec match. */
    public static boolean keepOriginal(ImageKind kind, int width, int height) {
        int[] s = size(kind);
        return s == null || (s[0] == width && s[1] == height);
    }

    private static final Pattern TRAILING_NUMBER = Pattern.compile("-?([0-9]{1,3})$");

    /**
     * Save order: desktop, mobile, logo, location, thumbnail, gallery, and within a kind by number
     * (Desktop-1 = day before Desktop-2 = night, Gallery-1 before Gallery-2). Rows are inserted in
     * this order, and the site lists banners and gallery images in insertion (id) order.
     */
    public static int saveRank(ImageKind kind, String typeToken) {
        int n = 1;
        Matcher m = TRAILING_NUMBER.matcher(typeToken == null ? "" : typeToken.trim().toLowerCase(Locale.ROOT));
        if (m.find()) {
            n = Integer.parseInt(m.group(1));
        }
        int k = switch (kind) {
            case DESKTOP -> 0;
            case MOBILE -> 1;
            case LOGO -> 2;
            case LOCATION -> 3;
            case THUMBNAIL -> 4;
            case GALLERY -> 5;
        };
        return k * 10_000 + n;
    }
}
