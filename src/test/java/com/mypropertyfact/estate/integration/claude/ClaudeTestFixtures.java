package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService;
import com.mypropertyfact.estate.integration.claude.service.ClaudeLookupService.*;
import com.mypropertyfact.estate.integration.claude.spec.ClaudeImporterSpec;
import com.mypropertyfact.estate.integration.claude.util.ClaudeText;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builders for workbooks, images, ZIPs and master data used by the connector tests. */
public final class ClaudeTestFixtures {

    private ClaudeTestFixtures() {
    }

    public static final List<String> HEADERS = ClaudeImporterSpec.REFERENCE_WORKBOOK_HEADERS;

    public static byte[] referenceWorkbook() throws IOException {
        try (InputStream in = ClaudeTestFixtures.class.getResourceAsStream("/claude/reference-property-list.xlsx")) {
            Objects.requireNonNull(in, "reference workbook fixture missing");
            return in.readAllBytes();
        }
    }

    /** A complete, valid row (values like the reference workbook) keyed by reference header. */
    public static Map<String, Object> validRow(String projectName, String builder, String city, String state, String rera) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ID", 1);
        r.put("BUILDER NAME", builder);
        r.put("PROJECT NAME", projectName);
        r.put("PROPERTY TYPE NAME", "Residential");
        r.put("PROJECT STATUS NAME", "Ready To Move");
        r.put(" PROJECT CONFIGURATION", "2 BHK-1183 sq.ft, 3 BHK-1511 sq.ft");
        r.put("PROJECT LOCALITY", "Sector 106");
        r.put("CITY NAME", city);
        r.put("STATE NAME", state);
        r.put("COUNTRY NAME", "India");
        r.put("RERA NO", rera);
        r.put("RERA WEBSITE", "https://www.up-rera.in/");
        r.put("IVR NO", 8920024793L);
        r.put("PROJECT PRICE", 0.75);
        r.put("META TITLE", projectName + " | Apartments");
        r.put("META DESCRIPTION", "Explore " + projectName + " with modern amenities and connectivity.");
        r.put("META KEYWORD", projectName + ", flats");
        r.put("AMENITY DESCRIPTION", projectName + " amenity description text that is long enough to be checked.");
        r.put("LOCATION DESCRIPTION", projectName + " location description text that is long enough to be checked.");
        r.put("FLOOR PLAN DESCRIPTION", projectName + " floor plan description text that is long enough to be checked.");
        r.put("BUILDER DESCRIPTION", builder + " is the developer of " + projectName + ".");
        r.put("PROJECT Amenities", "Swimming-Pool_Gym_Wi-Fi-Connectivity_Children's-Play-Area");
        r.put("LOCATION ADVANTAGE", "Golf-Course-Road-2.5-Km_Cyber Hub (8 Km)");
        r.put("PROJECT WALKTHROUGH DESCRIPTION", "Walkthrough of " + projectName + ".");
        for (int i = 1; i <= 4; i++) {
            r.put("Question " + i, "Question " + i + " about " + projectName + "?");
            r.put("Answer " + i, "Answer " + i + " for " + projectName + ".");
        }
        return r;
    }

    public static byte[] workbook(List<String> headers, List<Map<String, Object>> rows) throws IOException {
        return workbook("Sheet1", headers, rows);
    }

    public static byte[] workbook(String sheetName, List<String> headers, List<Map<String, Object>> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet(sheetName);
            Row h = s.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                h.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = s.createRow(r + 1);
                for (int i = 0; i < headers.size(); i++) {
                    Object v = rows.get(r).get(headers.get(i));
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Number n) {
                        row.createCell(i).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(i).setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] image(String format, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.drawString("MPF", 10, 20);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    public static Path zip(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(p))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return p;
    }

    /** Master data mirroring the local dev database (read-only SELECTs, 2026-11-23). */
    public static MasterData devLikeMasterData() {
        MasterData md = new MasterData();
        add(md.countries, new Ref(1, "India"));
        addState(md, new StateRef(1, "Uttar Pradesh", 1, "India"));
        addState(md, new StateRef(5, "Haryana", 1, "India"));
        addState(md, new StateRef(10, "Delhi", 1, "India"));
        addCity(md, new CityRef(2, "Noida", 1, "Uttar Pradesh", 1, "India"));
        addCity(md, new CityRef(15, "Ghaziabad", 1, "Uttar Pradesh", 1, "India"));
        addCity(md, new CityRef(17, "Gurugram", 5, "Haryana", 1, "India"));
        addCity(md, new CityRef(26, "Greater Noida", 1, "Uttar Pradesh", 1, "India"));
        addCity(md, new CityRef(27, "Noida extension", 1, "Uttar Pradesh", 1, "India"));
        String[] statuses = {"Completed", "Ready To Move", "New Launched", "Under Construction", "Ultra Luxury", "Featured"};
        for (int i = 0; i < statuses.length; i++) {
            add(md.statuses, new Ref(i + 1, statuses[i]));
        }
        String[] types = {"Residential", "Commercial", "New Launches"};
        for (int i = 0; i < types.length; i++) {
            add(md.types, new Ref(i + 1, types[i]));
        }
        String[] amenities = {"24 x 7 Water Supply", "24X7 Security", "Amphitheatre", "Badminton Court", "Banquet Hall",
                "Basketball Court", "Cctv Camera Security", "Cafeteria", "Car Parking", "Children's Play Area", "Club",
                "Community Hall", "Cricket Pitch", "Cycling Track", "Ev Charger", "Fire Fighting Systems", "Flower Garden",
                "Football Ground", "Gated Community", "Gym", "Indoor Games", "Jogging Track", "Library", "Lifts",
                "Multipurpose Court", "Multipurpose Hall", "Party Lawn", "Pet Park", "Power Backup", "Rain Water Harvesting",
                "Restaurant", "Swimming Pool", "Table Tennis", "Terrace Garden", "Waste Management", "Wi-fi Connectivity",
                "Yoga Meditation Area"};
        for (int i = 0; i < amenities.length; i++) {
            md.amenities.computeIfAbsent(ClaudeText.amenityKey(amenities[i]), k -> new ArrayList<>()).add(new Ref(100 + i, amenities[i]));
        }
        addBuilder(md, new BuilderRef(65, "Raheja Developers", "raheja-developers"));
        addBuilder(md, new BuilderRef(270, "Supertech", "supertech"));
        addBuilder(md, new BuilderRef(286, "Central Park", "central-park"));
        addBuilder(md, new BuilderRef(311, "Advitya Residency", "advitya-residency"));
        addBuilder(md, new BuilderRef(389, "Ansal API", "ansal-api"));
        addBuilder(md, new BuilderRef(423, "METRO SUITES HOMES LLP", "metro-suites-homes-llp"));
        return md;
    }

    public static void add(Map<String, List<Ref>> map, Ref ref) {
        map.computeIfAbsent(ClaudeText.lookupKey(ref.name()), k -> new ArrayList<>()).add(ref);
    }

    public static void addState(MasterData md, StateRef s) {
        md.states.computeIfAbsent(ClaudeText.lookupKey(s.name()), k -> new ArrayList<>()).add(s);
    }

    public static void addCity(MasterData md, CityRef c) {
        md.cities.computeIfAbsent(ClaudeText.lookupKey(c.name()), k -> new ArrayList<>()).add(c);
    }

    public static void addBuilder(MasterData md, BuilderRef b) {
        md.buildersByName.computeIfAbsent(ClaudeText.lookupKey(b.name()), k -> new ArrayList<>()).add(b);
        md.buildersBySlug.computeIfAbsent(b.slug(), k -> new ArrayList<>()).add(b);
    }

    public static ClaudeLookupService.ProjectRef project(int id, String name, boolean published, String rera) {
        return new ClaudeLookupService.ProjectRef(id, name, name.toLowerCase(Locale.ROOT).replace(' ', '-'), published, rera);
    }
}
