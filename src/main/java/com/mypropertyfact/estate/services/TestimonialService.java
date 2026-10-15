package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.config.CacheNames;
import com.mypropertyfact.estate.entities.Testimonial;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.ProjectRepository;
import com.mypropertyfact.estate.repositories.TestimonialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TestimonialService {

    private final TestimonialRepository testimonialRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<Testimonial> getAll() {
        return hydrateProjectNames(testimonialRepository.findAllByOrderByCreatedAtDesc());
    }

    @Cacheable(CacheNames.TESTIMONIALS_ACTIVE)
    @Transactional(readOnly = true)
    public List<Testimonial> getActive() {
        return hydrateProjectNames(testimonialRepository.findByStatusTrueOrderByCreatedAtDesc());
    }

    @Transactional
    public Response addUpdate(Testimonial testimonial) {
        Response response = new Response();
        if (testimonial == null) {
            response.setMessage("Testimonial payload is required.");
            return response;
        }

        String name = testimonial.getClientName() != null ? testimonial.getClientName().trim() : "";
        String text = testimonial.getTestimonialText() != null ? testimonial.getTestimonialText().trim() : "";
        String role = testimonial.getClientRole() != null ? testimonial.getClientRole().trim() : null;
        Integer projectId = testimonial.getProjectId();

        if (name.isEmpty()) {
            response.setMessage("Client name is required.");
            return response;
        }
        if (text.isEmpty()) {
            response.setMessage("Testimonial text is required.");
            return response;
        }
        if (projectId == null || projectId <= 0) {
            response.setMessage("Project is required.");
            return response;
        }

        var project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            response.setMessage("Selected project was not found.");
            return response;
        }
        String projectName = project.getProjectName() != null ? project.getProjectName().trim() : "";
        if (projectName.isEmpty()) {
            response.setMessage("Selected project has invalid name.");
            return response;
        }

        if (testimonial.getId() != null && testimonial.getId() > 0) {
            Optional<Testimonial> dbTestimonial = testimonialRepository.findById(testimonial.getId());
            if (dbTestimonial.isEmpty()) {
                response.setMessage("Testimonial not found.");
                return response;
            }
            Testimonial existing = dbTestimonial.get();
            existing.setClientName(name);
            existing.setClientRole(role == null || role.isBlank() ? null : role);
            existing.setProjectId(projectId);
            existing.setProjectName(projectName);
            existing.setTestimonialText(text);
            existing.setStatus(testimonial.getStatus() == null ? Boolean.TRUE : testimonial.getStatus());
            testimonialRepository.save(existing);
            response.setIsSuccess(1);
            response.setMessage("Testimonial updated successfully.");
            return response;
        }

        Testimonial newTestimonial = new Testimonial();
        newTestimonial.setClientName(name);
        newTestimonial.setClientRole(role == null || role.isBlank() ? null : role);
        newTestimonial.setProjectId(projectId);
        newTestimonial.setProjectName(projectName);
        newTestimonial.setTestimonialText(text);
        newTestimonial.setStatus(testimonial.getStatus() == null ? Boolean.TRUE : testimonial.getStatus());
        testimonialRepository.save(newTestimonial);

        response.setIsSuccess(1);
        response.setMessage("Testimonial saved successfully.");
        return response;
    }

    @Transactional
    public Response delete(Integer id) {
        Response response = new Response();
        if (id == null || id <= 0) {
            response.setMessage("Invalid testimonial id.");
            return response;
        }
        Optional<Testimonial> dbTestimonial = testimonialRepository.findById(id);
        if (dbTestimonial.isEmpty()) {
            response.setMessage("Testimonial not found.");
            return response;
        }

        testimonialRepository.deleteById(id);
        response.setIsSuccess(1);
        response.setMessage("Testimonial deleted successfully.");
        return response;
    }

    private List<Testimonial> hydrateProjectNames(List<Testimonial> testimonials) {
        if (testimonials == null || testimonials.isEmpty()) return testimonials;
        Set<Integer> missingIds = new HashSet<>();
        for (Testimonial item : testimonials) {
            if (item == null) continue;
            boolean hasName = item.getProjectName() != null && !item.getProjectName().trim().isEmpty();
            if (hasName || item.getProjectId() == null || item.getProjectId() <= 0) continue;
            missingIds.add(item.getProjectId());
        }
        if (missingIds.isEmpty()) return testimonials;
        Map<Integer, String> namesById = new HashMap<>();
        for (Object[] row : projectRepository.findNamesByIds(missingIds)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            String name = String.valueOf(row[1]).trim();
            if (!name.isEmpty()) namesById.put(((Number) row[0]).intValue(), name);
        }
        for (Testimonial item : testimonials) {
            if (item == null || item.getProjectId() == null) continue;
            boolean hasName = item.getProjectName() != null && !item.getProjectName().trim().isEmpty();
            if (hasName) continue;
            String name = namesById.get(item.getProjectId());
            if (name != null) item.setProjectName(name);
        }
        return testimonials;
    }
}
