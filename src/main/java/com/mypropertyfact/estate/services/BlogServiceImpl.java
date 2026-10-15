package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.common.FileUtils;
import com.mypropertyfact.estate.dtos.BlogDto;
import com.mypropertyfact.estate.entities.Blog;
import com.mypropertyfact.estate.entities.BlogCategory;
import com.mypropertyfact.estate.entities.City;
import com.mypropertyfact.estate.interfaces.BlogService;
import com.mypropertyfact.estate.models.BlogStatus;
import com.mypropertyfact.estate.models.ResourceNotFoundException;
import com.mypropertyfact.estate.models.Response;
import com.mypropertyfact.estate.repositories.BlogCategoryRepository;
import com.mypropertyfact.estate.repositories.BlogRepository;
import com.mypropertyfact.estate.repositories.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class BlogServiceImpl implements BlogService {

    private final BlogRepository blogRepository;

    private final BlogCategoryRepository blogCategoryRepository;

    private final CityRepository cityRepository;

    @Value("${upload_dir}")
    private String upload_dir;

    private final FileUtils fileUtils;

    private final AdminDashboardActivityService adminDashboardActivityService;

    @Override
    public Response addUpdateBlog(MultipartFile blogImage, BlogDto blogDto) {
        // Generate slug first
        String generatedSlug = fileUtils.generateSlug(blogDto.getSlugUrl());
        blogDto.setSlugUrl(generatedSlug);
        Optional<BlogCategory> blogCategory = blogCategoryRepository.findById(Integer.parseInt(blogDto.getBlogCategory()));
        Optional<City> city = cityRepository.findById(blogDto.getCityId());
        String blogImageName = null;
        Blog existing = blogDto.getId() > 0 ? blogRepository.findById(blogDto.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog not found")) : new Blog();
        if (blogImage != null && !blogImage.isEmpty()) {
            // Validate file
            if (!fileUtils.isFileSizeValid(blogImage, 5 * 1024 * 1024)) {
                throw new IllegalArgumentException("File size exceeds the 2MB limit.");
            }
            if (!fileUtils.isTypeImage(blogImage)) {
                throw new IllegalArgumentException("Invalid file type.");
            }
            // Delete old image BEFORE saving new one
            if (blogDto.getId() > 0) {
                existing = blogRepository.findById(blogDto.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog not found"));

                if (existing.getBlogImage() != null && !existing.getBlogImage().isBlank()) {
                    fileUtils.deleteFileFromDestination(existing.getBlogImage(), upload_dir + "blogDto/");
                }
            }
            // Rename and save image
            String imageName = fileUtils.renameFile(blogImage, blogDto.getSlugUrl());
            String dir = Paths.get(upload_dir, "blog/").toString();
            blogImageName = fileUtils.saveFile(blogImage, imageName, dir, 1200, 628, 0.9f); // Save resized and converted
        }

        // Update blogDto
        if (blogDto.getId() > 0) {
            // Delete old image only if new one is uploaded
            if (blogImageName != null && existing.getBlogImage() != null) {
                fileUtils.deleteFileFromDestination(existing.getBlogImage(), upload_dir + "blogDto/");
            }

            // Update fields
            existing.setBlogTitle(blogDto.getBlogTitle());
            existing.setBlogDescription(blogDto.getBlogDescription());
            if(!existing.getSlugUrl().equals(blogDto.getSlugUrl())){
                existing.setSlugUrl(blogDto.getSlugUrl());
            }
            // Status is managed via /blog/update-status toggle — preserve on content edits.
            existing.setBlogKeywords(blogDto.getBlogKeywords());
            existing.setAuthorName(blogDto.getAuthorName());
            blogCategory.ifPresent(existing::setBlogCategory);
            city.ifPresent(existing::setCity);
            existing.setBlogMetaDescription(blogDto.getBlogMetaDescription());
            if (blogImageName != null) {
                existing.setBlogImage(blogImageName);
            }
            applyPublicationState(existing, blogDto);
            blogRepository.save(existing);
            adminDashboardActivityService.recordForCurrentUser(
                    AdminDashboardActivityService.TASK_BLOG,
                    publicationActivityLabel(existing) + " blog: " + blogDto.getBlogTitle(),
                    "/admin/dashboard/manage-blogs");
            return new Response(1, publicationSuccessMessage(existing), 0);
        }

        // Add new blogDto
        if (blogRepository.existsBySlugUrl(blogDto.getSlugUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug URL already exists");
        }
        Blog blog = new Blog();
        blogCategory.ifPresent(blog::setBlogCategory);
        city.ifPresent(blog::setCity);
        blog.setBlogTitle(blogDto.getBlogTitle());
        blog.setBlogDescription(blogDto.getBlogDescription());
        blog.setSlugUrl(blogDto.getSlugUrl());
        blog.setBlogKeywords(blogDto.getBlogKeywords());
        blog.setAuthorName(blogDto.getAuthorName());
        blog.setBlogMetaDescription(blogDto.getBlogMetaDescription());
        blog.setBlogImage(blogImageName);
        applyPublicationState(blog, blogDto);
        blogRepository.save(blog);
        adminDashboardActivityService.recordForCurrentUser(
                AdminDashboardActivityService.TASK_BLOG,
                publicationActivityLabel(blog) + " blog: " + blogDto.getBlogTitle(),
                "/admin/dashboard/manage-blogs");
        return new Response(1, publicationSuccessMessage(blog), 0);
    }

    private void applyPublicationState(Blog blog, BlogDto blogDto) {
        int requestedStatus = blogDto.getStatus();

        if (requestedStatus == BlogStatus.DRAFT) {
            blog.setStatus(BlogStatus.DRAFT);
            blog.setScheduledPublishAt(null);
            return;
        }

        if (requestedStatus == BlogStatus.SCHEDULED) {
            LocalDateTime scheduledAt = resolveScheduledPublishAt(blogDto.getScheduledPublishAt());
            if (scheduledAt == null) {
                throw new IllegalArgumentException("Scheduled publish date and time are required.");
            }
            if (!scheduledAt.isAfter(LocalDateTime.now())) {
                throw new IllegalArgumentException("Scheduled publish time must be in the future.");
            }
            blog.setStatus(BlogStatus.SCHEDULED);
            blog.setScheduledPublishAt(scheduledAt);
            return;
        }

        blog.setStatus(BlogStatus.PUBLISHED);
        blog.setScheduledPublishAt(null);
    }

    private LocalDateTime resolveScheduledPublishAt(LocalDateTime scheduledPublishAt) {
        return scheduledPublishAt;
    }

    private String publicationSuccessMessage(Blog blog) {
        return switch (blog.getStatus()) {
            case BlogStatus.DRAFT -> "Blog saved as draft.";
            case BlogStatus.SCHEDULED -> "Blog scheduled successfully.";
            default -> "Blog published successfully.";
        };
    }

    private String publicationActivityLabel(Blog blog) {
        return switch (blog.getStatus()) {
            case BlogStatus.DRAFT -> "Saved draft";
            case BlogStatus.SCHEDULED -> "Scheduled";
            default -> "Published";
        };
    }

    private BlogDto mapToDto(Blog blog) {
        BlogDto blogDto = new BlogDto(
                blog.getId(),
                blog.getBlogTitle(),
                blog.getBlogKeywords(),
                blog.getBlogMetaDescription(),
                blog.getBlogDescription(),
                blog.getSlugUrl(),
                blog.getBlogImage(),
                blog.getAuthorName(),
                blog.getBlogCategory() != null ? blog.getBlogCategory().getCategoryName() : null,
                blog.getStatus(),
                blog.getBlogCategory() != null ? blog.getBlogCategory().getId() : 0,
                blog.getCity() != null ? blog.getCity().getId() : 0,
                blog.getCity() != null ? blog.getCity().getName() : null,
                blog.getCreatedAt(),
                blog.getScheduledPublishAt()
        );
        return blogDto;
    }

    @Override
    public Optional<Blog> getBlogById(int id) {
        if (!blogRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog not found");
        }
        return blogRepository.findById(id);
    }

    @Override
    public List<BlogDto> getAllBlogs() {
        return blogRepository.findAllWithBlogCategory().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public BlogDto getBySlug(String slug) {
        Blog bySlugUrl = blogRepository.findBySlugUrl(slug);
        if (bySlugUrl == null || !BlogStatus.isPubliclyVisible(bySlugUrl.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog not found");
        }
        return mapToDto(bySlugUrl);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BlogDto> getWithPagination(int page, int size, String from, String search) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500));
        boolean marketOnly = !"blog".equalsIgnoreCase(from);
        String trimmed = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String pattern = trimmed.isEmpty()
                ? null
                : "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        Page<Object[]> rows = blogRepository.findPublicCards(BlogStatus.PUBLISHED, marketOnly, pattern, pageRequest);
        List<BlogDto> cards = rows.getContent().stream().map(row -> {
            BlogDto blogDto = new BlogDto();
            blogDto.setId(row[0] == null ? 0 : ((Number) row[0]).intValue());
            blogDto.setBlogTitle((String) row[1]);
            blogDto.setBlogMetaDescription((String) row[2]);
            blogDto.setSlugUrl((String) row[3]);
            blogDto.setBlogImage((String) row[4]);
            blogDto.setAuthorName((String) row[5]);
            blogDto.setBlogCategory((String) row[6]);
            blogDto.setCategoryId(row[7] == null ? 0 : ((Number) row[7]).intValue());
            blogDto.setCityId(row[8] == null ? 0 : ((Number) row[8]).intValue());
            blogDto.setCityName((String) row[9]);
            blogDto.setCreatedAt((LocalDateTime) row[10]);
            blogDto.setStatus(row[11] == null ? 0 : ((Number) row[11]).intValue());
            return blogDto;
        }).toList();
        return new PageImpl<>(cards, pageRequest, rows.getTotalElements());
    }



    @Override
    public Response deleteBlog(int id) {
        Blog blog = blogRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Blog already deleted or not found"));
        fileUtils.deleteFileFromDestination(blog.getBlogImage(), upload_dir + "blog/");
        blogRepository.delete(blog);
        return new Response(1, "Blog deleted successful...", 0);
    }

    @Override
    public Response updateBlogStatus(int id, int status) {
        Blog blog = blogRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog not found"));

        if (blog.getStatus() == BlogStatus.DRAFT || blog.getStatus() == BlogStatus.SCHEDULED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use Publish to make draft or scheduled blogs live.");
        }

        int normalized = status == BlogStatus.PUBLISHED ? BlogStatus.PUBLISHED : BlogStatus.INACTIVE;
        blog.setStatus(normalized);
        blog.setScheduledPublishAt(null);
        blogRepository.save(blog);
        adminDashboardActivityService.recordForCurrentUser(
                AdminDashboardActivityService.TASK_BLOG,
                (normalized == BlogStatus.PUBLISHED ? "Activated" : "Deactivated") + " blog: " + blog.getBlogTitle(),
                "/admin/dashboard/manage-blogs");
        return new Response(1, normalized == BlogStatus.PUBLISHED ? "Blog is now active" : "Blog is now inactive", 0);
    }

    @Override
    public Response publishBlog(int id) {
        Blog blog = blogRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog not found"));

        blog.setStatus(BlogStatus.PUBLISHED);
        blog.setScheduledPublishAt(null);
        blogRepository.save(blog);

        adminDashboardActivityService.recordForCurrentUser(
                AdminDashboardActivityService.TASK_BLOG,
                "Published blog: " + blog.getBlogTitle(),
                "/admin/dashboard/manage-blogs");

        return new Response(1, "Blog published successfully.", 0);
    }
}
