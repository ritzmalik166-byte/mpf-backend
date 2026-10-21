package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.ConstantMessages;
import com.mypropertyfact.estate.common.CommonMapper;
import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.dtos.*;
import com.mypropertyfact.estate.entities.*;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.CityRepository;
import com.mypropertyfact.estate.repositories.LocalityRepository;
import com.mypropertyfact.estate.repositories.ProjectRepository;
import com.mypropertyfact.estate.repositories.StateRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class CityService {
    private static final int MONUMENT_MAX_W = 1920;
    private static final int MONUMENT_MAX_H = 1080;
    private static final float MONUMENT_QUALITY = 0.85f;

    private final CityRepository cityRepository;
    private final StateRepository stateRepository;
    private final CommonMapper commonMapper;
    private final FileUtils fileUtils;
    private final ProjectRepository projectRepository;
    private final LocalityRepository localityRepository;

    @Value("${upload_dir}")
    private String uploadDir;

    @Transactional
    public List<CityDetailDto> getAllCities() {
        List<CityDetailDto> allCities = cityRepository.findAllCities();
        return allCities.stream().map(city -> {
            List<LocalityShortDto> allLocalitiesOfCity = localityRepository.findAllLocalitiesOfCity(city.getId());
            city.setLocalities(allLocalitiesOfCity);
            return city;
        }).toList();
    }

    public Response postNewCity(CityDto cityDto) {
        return saveCity(cityDto, null);
    }

    public Response saveCityWithImage(CityDto cityDto, MultipartFile monumentImage) {
        return saveCity(cityDto, monumentImage);
    }

    private Response saveCity(CityDto cityDto, MultipartFile monumentImage) {
        try {
            City existingCity = this.cityRepository.findByName(cityDto.getCityName());
            if (existingCity != null && existingCity.getId() != cityDto.getId()) {
                return new Response(0, ConstantMessages.CITY_EXISTS, 0);
            }

            String slug = cityDto.getSlugURL();
            if (slug == null || slug.isBlank()) {
                slug = fileUtils.generateSlug(cityDto.getCityName());
            } else {
                slug = fileUtils.generateSlug(slug);
            }
            cityDto.setSlugURL(slug);

            Optional<State> state = stateRepository.findById(cityDto.getStateId());
            if (cityDto.getId() != 0) {
                Optional<City> savedCity = cityRepository.findById(cityDto.getId());
                if (savedCity.isEmpty()) {
                    return new Response(0, "City not found", 0);
                }
                savedCity.ifPresent(city -> {
                    state.ifPresent(city::setState);
                    commonMapper.mapCityToCityDto(city, cityDto);
                    handleMonumentImageUpload(city, monumentImage);
                    cityRepository.save(city);
                });
                return new Response(1, ConstantMessages.CITY_UPDATED, 0);
            } else {
                City city = new City();
                state.ifPresent(city::setState);
                commonMapper.mapCityToCityDto(city, cityDto);
                if (city.getIsActive() == null) {
                    city.setIsActive(true);
                }
                handleMonumentImageUpload(city, monumentImage);
                cityRepository.save(city);
                return new Response(1, ConstantMessages.CITY_ADDED, 0);
            }
        } catch (IllegalArgumentException ex) {
            return new Response(0, ex.getMessage(), 0);
        }
    }

    private void handleMonumentImageUpload(City city, MultipartFile monumentImage) {
        if (monumentImage == null || monumentImage.isEmpty()) {
            return;
        }
        if (!fileUtils.isTypeImage(monumentImage)) {
            throw new IllegalArgumentException("Only image files are allowed for monument image.");
        }
        String cityUploadDir = Paths.get(uploadDir, "cities").toString();
        String slug = city.getSlugUrl() != null ? city.getSlugUrl() : "city";
        String savedFileName = fileUtils.saveImageWithinBounds(
                monumentImage,
                slug + "-monument-" + System.currentTimeMillis(),
                cityUploadDir,
                MONUMENT_MAX_W,
                MONUMENT_MAX_H,
                MONUMENT_QUALITY
        );
        if (savedFileName != null && !savedFileName.isBlank()) {
            if (city.getMonumentImage() != null && !city.getMonumentImage().isBlank()) {
                fileUtils.deleteFileFromDestination(city.getMonumentImage(), cityUploadDir);
            }
            city.setMonumentImage(savedFileName);
        }
    }

    public Response deleteCity(int id) {
        Response response = new Response();
        try {
            City city = this.cityRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("City Not Found !"));
            if (city != null) {
                this.cityRepository.deleteById(id);
                response.setIsSuccess(1);
                response.setMessage(ConstantMessages.CITY_DELETED);
            }
        } catch (Exception e) {
            response.setMessage(e.getMessage());
            response.setIsSuccess(0);
        }
        return response;
    }

    @Transactional
    public CityDetailDto getBySlug(String url) {
        CityDetailDto dbCity = this.cityRepository.findCityDetails(url);
        if (dbCity == null) {
            return null;
        }
        if (Boolean.FALSE.equals(dbCity.getIsActive())) {
            return null;
        }
        List<LocalityShortDto> allLocalitiesOfCity = localityRepository.findAllLocalitiesOfCity(dbCity.getId());
        dbCity.setLocalities(allLocalitiesOfCity);
        List<ProjectShortDetails> allProjects = projectRepository.findAllProjects();
        allProjects = allProjects.stream()
                .filter(project -> project.getCitySlug() != null
                        && project.getCitySlug().trim().equals(url.trim()))
                .toList();
        dbCity.setProjectList(allProjects);
        return dbCity;
    }

    public Response addUpdateCity(MultipartFile cityImage, City city) {
        return new Response();
    }

    /**
     * Bulk upload city monument images from a single ZIP file.
     * File names must match city slug/name, e.g. {@code agra.jpg} or {@code agra-monument.png}.
     */
    @Transactional
    public Response uploadCityMonumentsZip(MultipartFile monumentsZip) {
        if (monumentsZip == null || monumentsZip.isEmpty()) {
            return new Response(0, "ZIP file is required.", 0);
        }
        String zipName = monumentsZip.getOriginalFilename();
        if (zipName == null || !zipName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return new Response(0, "Only .zip file is allowed.", 0);
        }
        try {
            Map<String, City> bySlug = new HashMap<>();
            for (City city : cityRepository.findAll()) {
                if (city.getSlugUrl() != null && !city.getSlugUrl().isBlank()) {
                    bySlug.put(city.getSlugUrl().trim().toLowerCase(Locale.ROOT), city);
                }
                if (city.getName() != null && !city.getName().isBlank()) {
                    bySlug.putIfAbsent(
                            fileUtils.generateSlug(city.getName()).toLowerCase(Locale.ROOT), city);
                }
            }
            if (bySlug.isEmpty()) {
                return new Response(0, "No cities found to map monument images.", 0);
            }

            String cityUploadDir = Paths.get(uploadDir, "cities").toString();
            Map<String, byte[]> extracted = new LinkedHashMap<>();
            extractZipToMap(monumentsZip.getInputStream(), extracted);

            int updated = 0;
            int skipped = 0;
            List<String> unmatched = new ArrayList<>();
            Set<Integer> touchedCityIds = new HashSet<>();

            for (Map.Entry<String, byte[]> entry : extracted.entrySet()) {
                String fileName = entry.getKey();
                if (!isImageFileName(fileName)) {
                    skipped++;
                    continue;
                }
                City city = bySlug.get(resolveCityKeyFromFileName(fileName));
                if (city == null) {
                    unmatched.add(fileName);
                    continue;
                }
                String saved = fileUtils.saveImageFromBytes(
                        entry.getValue(), fileName, cityUploadDir, MONUMENT_MAX_W, MONUMENT_MAX_H);
                if (saved == null || saved.isBlank()) {
                    skipped++;
                    continue;
                }
                String oldImage = city.getMonumentImage();
                if (oldImage != null && !oldImage.isBlank() && !oldImage.equals(saved)) {
                    fileUtils.deleteFileFromDestination(oldImage, cityUploadDir);
                }
                city.setMonumentImage(saved);
                city.setUpdatedAt(LocalDateTime.now());
                touchedCityIds.add(city.getId());
                updated++;
            }

            for (City city : bySlug.values()) {
                if (touchedCityIds.contains(city.getId())) {
                    cityRepository.save(city);
                }
            }

            String msg = "Bulk monument upload done. Updated " + updated + " city/cities";
            if (skipped > 0) {
                msg += ", skipped " + skipped + " file(s)";
            }
            if (!unmatched.isEmpty()) {
                msg += ", unmatched: " + String.join(", ", unmatched.stream().limit(8).toList());
                if (unmatched.size() > 8) {
                    msg += " ...";
                }
            }
            msg += ".";
            return new Response(1, msg, 0);
        } catch (Exception e) {
            log.error("uploadCityMonumentsZip failed: {}", e.getMessage());
            return new Response(0, e.getMessage() != null ? e.getMessage() : "Bulk upload failed.", 0);
        }
    }

    private String resolveCityKeyFromFileName(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String normalized = fileUtils.generateSlug(base).toLowerCase(Locale.ROOT);
        if (normalized.endsWith("-monument")) {
            normalized = normalized.substring(0, normalized.length() - 9);
        } else if (normalized.endsWith("-landmark")) {
            normalized = normalized.substring(0, normalized.length() - 9);
        }
        return normalized;
    }

    private void extractZipToMap(InputStream zipIs, Map<String, byte[]> out) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(zipIs)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.contains("..") || name.contains("\\")) {
                    continue;
                }
                if (name.contains("/")) {
                    name = name.substring(name.lastIndexOf('/') + 1);
                }
                if (name.isBlank() || name.startsWith(".") || name.startsWith("__MACOSX")) {
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                out.put(name, baos.toByteArray());
            }
        }
    }

    private static boolean isImageFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp");
    }
}
