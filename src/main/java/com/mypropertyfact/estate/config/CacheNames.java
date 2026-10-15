package com.mypropertyfact.estate.config;

import java.util.List;

/**
 * Public read caches. All entries are cleared every {@code mpf.cache.ttl-ms} and whenever a
 * catalog entity changes (see {@link CatalogCacheEvictionListener}).
 */
public final class CacheNames {
    public static final String PROJECT_SHORT_DETAILS = "projectShortDetails";
    public static final String CITY_ALL = "cityAll";
    public static final String CITY_NAV = "cityNav";
    public static final String BUILDER_ALL = "builderAll";
    public static final String BUILDER_LOGOS = "builderLogos";
    public static final String TESTIMONIALS_ACTIVE = "testimonialsActive";

    public static final List<String> ALL = List.of(
            PROJECT_SHORT_DETAILS,
            CITY_ALL,
            CITY_NAV,
            BUILDER_ALL,
            BUILDER_LOGOS,
            TESTIMONIALS_ACTIVE
    );

    private CacheNames() {
    }
}
