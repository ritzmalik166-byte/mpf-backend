package com.mypropertyfact.estate.controllers;

import com.mypropertyfact.estate.dtos.CityDetailDto;
import com.mypropertyfact.estate.dtos.CityDto;
import com.mypropertyfact.estate.entities.City;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.services.CityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/city")
@RequiredArgsConstructor
public class CityController {

    private final CityService cityService;

    @GetMapping("/all")
    public ResponseEntity<?> getAllCities() {
        return new ResponseEntity<>(cityService.getAllCities(), HttpStatus.OK);
    }

    /** Slim city list for header, footer and search filters (no descriptions, meta or localities). */
    @GetMapping("/nav")
    public ResponseEntity<?> getCityNav() {
        return new ResponseEntity<>(cityService.getCityNav(), HttpStatus.OK);
    }

    @PostMapping("/add-new")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<?> postNewCity(@RequestBody CityDto cityDto) {
        return new ResponseEntity<>(this.cityService.postNewCity(cityDto), HttpStatus.CREATED);
    }

    @PostMapping("/save")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> saveCityWithImage(
            @RequestParam(value = "monumentImageFile", required = false) MultipartFile monumentImageFile,
            @ModelAttribute CityDto cityDto) {
        Response response = cityService.saveCityWithImage(cityDto, monumentImageFile);
        if (response.getIsSuccess() == 1) {
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @DeleteMapping("/delete/{id}")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<?> deleteCity(@PathVariable("id") int id) {
        return new ResponseEntity<>(this.cityService.deleteCity(id), HttpStatus.OK);
    }

    @GetMapping("/get/{url}")
    public ResponseEntity<CityDetailDto> getBySlug(@PathVariable("url") String url) {
        return new ResponseEntity<>(this.cityService.getBySlug(url), HttpStatus.OK);
    }

    @PostMapping("/add-update")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> addUpdateCity(@RequestParam(required = false) MultipartFile cityImage,
                                                  @RequestBody City city) {
        return new ResponseEntity<>(cityService.addUpdateCity(cityImage, city), HttpStatus.OK);
    }

    /**
     * Bulk upload city monument images from a single ZIP file.
     * File names must match city slug/name, e.g. {@code agra.jpg} or {@code agra-monument.png}.
     */
    @PostMapping("/upload-city-monuments-zip")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> uploadCityMonumentsZip(
            @RequestParam("monumentsZip") MultipartFile monumentsZip) {
        Response response = cityService.uploadCityMonumentsZip(monumentsZip);
        return ResponseEntity.status(
                        response.getIsSuccess() == 1 ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .body(response);
    }
}
