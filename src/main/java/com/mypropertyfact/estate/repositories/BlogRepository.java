package com.mypropertyfact.estate.repositories;

import com.mypropertyfact.estate.entities.Blog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BlogRepository extends JpaRepository<Blog, Integer> {

    // To check if a blog with the given slug exists
    boolean existsBySlugUrl(String slugUrl);

    // To fetch a blog by its slug URL
    Blog findBySlugUrl(String slugUrl);

    @EntityGraph(attributePaths = {"blogCategory", "city"})
    @Query("SELECT b FROM Blog b")
    List<Blog> findAllWithBlogCategory();

    long countByStatus(int status);

    /**
     * Public blog cards without the article body. Rows are
     * [id, title, metaDescription, slugUrl, image, authorName, categoryName, categoryId, cityId, cityName, createdAt, status].
     * Category 5 is the market-analysis category.
     */
    @Query(value = """
            SELECT b.id, b.blogTitle, b.blogMetaDescription, b.slugUrl, b.blogImage, b.authorName,
                   bc.categoryName, bc.id, c.id, c.name, b.createdAt, b.status
            FROM Blog b
            LEFT JOIN b.blogCategory bc
            LEFT JOIN b.city c
            WHERE b.status = :status
              AND ((:marketOnly = true AND bc.id = 5)
                   OR (:marketOnly = false AND (bc.id IS NULL OR bc.id <> 5)))
              AND (:search IS NULL
                   OR LOWER(b.blogTitle) LIKE :search
                   OR LOWER(b.blogMetaDescription) LIKE :search)
            ORDER BY b.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(b)
            FROM Blog b
            LEFT JOIN b.blogCategory bc
            WHERE b.status = :status
              AND ((:marketOnly = true AND bc.id = 5)
                   OR (:marketOnly = false AND (bc.id IS NULL OR bc.id <> 5)))
              AND (:search IS NULL
                   OR LOWER(b.blogTitle) LIKE :search
                   OR LOWER(b.blogMetaDescription) LIKE :search)
            """)
    Page<Object[]> findPublicCards(
            @Param("status") int status,
            @Param("marketOnly") boolean marketOnly,
            @Param("search") String search,
            Pageable pageable);

    List<Blog> findByStatusAndScheduledPublishAtLessThanEqual(int status, LocalDateTime scheduledPublishAt);
}
