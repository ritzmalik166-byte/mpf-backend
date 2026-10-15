package com.mypropertyfact.estate.controllers;

import com.mypropertyfact.estate.dtos.BuilderLogoDto;
import com.mypropertyfact.estate.dtos.BuilderResponse;
import com.mypropertyfact.estate.dtos.BuilderDto;
import com.mypropertyfact.estate.dtos.BuilderWriteDto;
import com.mypropertyfact.estate.entities.Builder;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.services.BuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/builder")
@RequiredArgsConstructor
public class BuilderController {

    private final BuilderService builderService;

    @GetMapping("/get-all")
    public ResponseEntity<BuilderResponse> getAllBuilders() {
        return new ResponseEntity<>(builderService.getAllBuilders(), HttpStatus.OK);
    }

    @GetMapping("/get-all-builders")
    public ResponseEntity<List<Builder>> getAllBuildersList() {
        return ResponseEntity.ok(builderService.getAllBuildersList());
    }

    /** Slim list (id, name, slug, logo) of builders that have a logo; used by the home marquee. */
    @GetMapping("/with-logos")
    public ResponseEntity<List<BuilderLogoDto>> getBuildersWithLogos() {
        return ResponseEntity.ok(builderService.getBuildersWithLogos());
    }

    @PostMapping("/add-update")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> addUpdateBuilder(@RequestBody BuilderWriteDto dto) {
        Response response = this.builderService.addUpdateBuilder(dto);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /**
     * Upload developer logo image and/or a ZIP of gallery images. Files are stored under
     * {@code {upload_dir}/builders/{slugUrl}/} and served via
     * {@code GET /api/v1/get/images/builders/{slugUrl}/{filename}}.
     */
    @PostMapping("/upload-developer-media")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> uploadDeveloperMedia(
            @RequestParam("builderId") int builderId,
            @RequestParam(value = "logo", required = false) MultipartFile logo,
            @RequestParam(value = "galleryZip", required = false) MultipartFile galleryZip) {
        Response response = builderService.uploadDeveloperMedia(builderId, logo, galleryZip);
        return ResponseEntity.status(
                        response.getIsSuccess() == 1 ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Delete only the selected developer's logo (file + DB field). Gallery is not affected.
     */
    @DeleteMapping("/delete-developer-logo/{builderId}")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> deleteDeveloperLogo(@PathVariable("builderId") int builderId) {
        Response response = builderService.deleteDeveloperLogo(builderId);
        return ResponseEntity.status(
                        response.getIsSuccess() == 1 ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Bulk upload builder logos from a single ZIP file.
     * File names must match builder slug/name, e.g. {@code saya-homes.jpg} or {@code saya-homes-logo.png}.
     */
    @PostMapping("/upload-builder-logos-zip")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> uploadBuilderLogosZip(
            @RequestParam("logosZip") MultipartFile logosZip) {
        Response response = builderService.uploadBuilderLogosZip(logosZip);
        return ResponseEntity.status(
                        response.getIsSuccess() == 1 ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .body(response);
    }
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("@adminPermissionService.can(authentication, 'MANAGE_OPTIONS')")
    public ResponseEntity<Response> deleteBuilder(@PathVariable("id") int id){
        return new ResponseEntity<>(this.builderService.deleteBuilder(id), HttpStatus.OK);
    }
    @GetMapping("/get/{url}")
    public ResponseEntity<BuilderDto> getBuilderBySlug(@PathVariable("url") String url){
        return new ResponseEntity<>(this.builderService.getBySlug(url), HttpStatus.OK);
    }
}
