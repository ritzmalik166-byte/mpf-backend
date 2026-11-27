package com.mypropertyfact.estate.integration.claude.importer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Lossless metadata removal for images that are stored as originals. Only container segments that
 * carry metadata are dropped (EXIF incl. GPS, XMP, IPTC, comments, text chunks); the compressed
 * image data is copied byte for byte, so pixels, dimensions and format do not change. Colour
 * profiles (JPEG APP2 ICC, PNG iCCP, WebP ICCP) and the Adobe APP14 marker are kept.
 *
 * <p>Every method throws {@link IllegalArgumentException} on a structure it does not understand;
 * the caller then falls back to the existing re-encoding path.
 */
public final class ClaudeImageMetadata {

    private ClaudeImageMetadata() {
    }

    public static byte[] strip(String format, byte[] data) {
        return switch (format) {
            case "jpg" -> stripJpeg(data);
            case "png" -> stripPng(data);
            case "webp" -> stripWebp(data);
            default -> throw new IllegalArgumentException("unsupported format " + format);
        };
    }

    /* ------------------------------------------------------------------ JPEG */

    /** APPn markers that are dropped: APP1 (EXIF/XMP), APP3-APP13 (incl. IPTC), APP15, COM. Kept: APP0, APP2, APP14. */
    private static boolean dropJpegMarker(int m) {
        return m == 0xE1 || (m >= 0xE3 && m <= 0xED) || m == 0xEF || m == 0xFE;
    }

    static byte[] stripJpeg(byte[] d) {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("not a JPEG");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 2);
        int p = 2;
        while (p < d.length) {
            if ((d[p] & 0xFF) != 0xFF) {
                throw new IllegalArgumentException("JPEG marker expected at " + p);
            }
            int q = p;
            while (q < d.length && (d[q] & 0xFF) == 0xFF) {
                q++;
            }
            if (q >= d.length) {
                throw new IllegalArgumentException("truncated JPEG");
            }
            int m = d[q] & 0xFF;
            if (m == 0xDA) { // start of scan: entropy-coded data and the rest are copied unchanged
                out.write(d, p, d.length - p);
                return out.toByteArray();
            }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                out.write(d, p, q + 1 - p);
                p = q + 1;
                continue;
            }
            if (m == 0xD9) {
                out.write(d, p, q + 1 - p);
                return out.toByteArray();
            }
            if (q + 2 >= d.length) {
                throw new IllegalArgumentException("truncated JPEG segment");
            }
            int len = ((d[q + 1] & 0xFF) << 8) | (d[q + 2] & 0xFF);
            int end = q + 1 + len;
            if (len < 2 || end > d.length) {
                throw new IllegalArgumentException("bad JPEG segment length");
            }
            if (!dropJpegMarker(m)) {
                out.write(d, p, end - p);
            }
            p = end;
        }
        throw new IllegalArgumentException("JPEG has no image data");
    }

    /**
     * EXIF orientation of a JPEG (1 = normal), or 1 when there is none. Browsers apply it, and
     * removing it would rotate the displayed image, so the caller does not keep originals whose
     * orientation is not 1.
     */
    public static int jpegOrientation(byte[] d) {
        int p = 2;
        while (p + 4 < d.length && (d[p] & 0xFF) == 0xFF) {
            int m = d[p + 1] & 0xFF;
            if (m == 0xDA || m == 0xD9) {
                break;
            }
            int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > d.length) {
                break;
            }
            if (m == 0xE1 && len >= 16 && new String(d, p + 4, 6, StandardCharsets.ISO_8859_1).equals("Exif\0\0")) {
                Integer o = tiffOrientation(d, p + 10, p + 2 + len);
                if (o != null) {
                    return o;
                }
            }
            p += 2 + len;
        }
        return 1;
    }

    private static Integer tiffOrientation(byte[] d, int t, int limit) {
        if (t + 8 > limit) {
            return null;
        }
        boolean le = d[t] == 'I' && d[t + 1] == 'I';
        if (!le && !(d[t] == 'M' && d[t + 1] == 'M')) {
            return null;
        }
        int ifd = t + (int) u32(d, t + 4, le);
        if (ifd + 2 > limit || ifd < t) {
            return null;
        }
        int n = u16(d, ifd, le);
        for (int i = 0; i < n; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > limit) {
                return null;
            }
            if (u16(d, e, le) == 0x0112) {
                return u16(d, e + 8, le);
            }
        }
        return null;
    }

    private static int u16(byte[] d, int i, boolean le) {
        return le ? (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 : (d[i] & 0xFF) << 8 | (d[i + 1] & 0xFF);
    }

    private static long u32(byte[] d, int i, boolean le) {
        return le ? (u16(d, i, true) | (long) u16(d, i + 2, true) << 16) : ((long) u16(d, i, false) << 16 | u16(d, i + 2, false));
    }

    /* ------------------------------------------------------------------ PNG */

    private static final Set<String> PNG_DROP = Set.of("tEXt", "zTXt", "iTXt", "eXIf", "tIME");

    static byte[] stripPng(byte[] d) {
        if (d.length < 8 || (d[0] & 0xFF) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') {
            throw new IllegalArgumentException("not a PNG");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 8);
        int p = 8;
        while (p + 12 <= d.length) {
            long len = u32(d, p, false);
            String type = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            long end = p + 12 + len;
            if (len < 0 || end > d.length) {
                throw new IllegalArgumentException("bad PNG chunk length");
            }
            if (!PNG_DROP.contains(type)) {
                out.write(d, p, (int) (end - p));
            }
            p = (int) end;
            if ("IEND".equals(type)) {
                return out.toByteArray();
            }
        }
        throw new IllegalArgumentException("PNG has no IEND");
    }

    /* ------------------------------------------------------------------ WebP */

    static byte[] stripWebp(byte[] d) {
        if (d.length < 20 || !new String(d, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                || !new String(d, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP")) {
            throw new IllegalArgumentException("not a WebP");
        }
        long riffEnd = Math.min(d.length, 8 + u32(d, 4, true));
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 12);
        int p = 12;
        int vp8xFlagsAt = -1;
        while (p + 8 <= riffEnd) {
            String id = new String(d, p, 4, StandardCharsets.ISO_8859_1);
            long len = u32(d, p + 4, true);
            long end = p + 8 + len + (len & 1);
            if (end > riffEnd + 1 || p + 8 + len > d.length) {
                throw new IllegalArgumentException("bad WebP chunk length");
            }
            end = Math.min(end, d.length);
            if (!"EXIF".equals(id) && !"XMP ".equals(id)) {
                if ("VP8X".equals(id)) {
                    vp8xFlagsAt = out.size() + 8;
                }
                out.write(d, p, (int) (end - p));
            }
            p = (int) end;
        }
        byte[] r = out.toByteArray();
        if (vp8xFlagsAt >= 0 && vp8xFlagsAt < r.length) {
            r[vp8xFlagsAt] = (byte) (r[vp8xFlagsAt] & ~0x0C); // clear EXIF (0x08) and XMP (0x04) flags
        }
        long riffSize = r.length - 8L;
        r[4] = (byte) riffSize;
        r[5] = (byte) (riffSize >> 8);
        r[6] = (byte) (riffSize >> 16);
        r[7] = (byte) (riffSize >> 24);
        return r;
    }
}
