package com.mypropertyfact.estate.config;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps public read caches fresh: clears them when a catalog entity is written and on a fixed
 * interval (covers bulk JPQL updates that bypass entity callbacks).
 * <p>
 * Hibernate may instantiate entity listeners itself, so the cache manager is held statically.
 */
@Component
public class CatalogCacheEvictionListener {

    private static volatile CacheManager cacheManager;

    @Autowired
    public void setCacheManager(CacheManager manager) {
        cacheManager = manager;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void onCatalogEntityChange(Object entity) {
        clearAll();
    }

    @Scheduled(fixedRateString = "${mpf.cache.ttl-ms:60000}", initialDelayString = "${mpf.cache.ttl-ms:60000}")
    public void expireCaches() {
        clearAll();
    }

    public static void clearAll() {
        CacheManager manager = cacheManager;
        if (manager == null) return;
        for (String name : CacheNames.ALL) {
            Cache cache = manager.getCache(name);
            if (cache != null) cache.clear();
        }
    }
}
