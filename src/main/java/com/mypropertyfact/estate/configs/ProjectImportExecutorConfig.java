package com.mypropertyfact.estate.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProjectImportExecutorConfig {

    /** Runs whole bulk project imports in the background, one or two at a time. */
    @Bean(name = "projectImportJobExecutor")
    public ThreadPoolTaskExecutor projectImportJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("project-import-");
        executor.initialize();
        return executor;
    }

    /**
     * Resizes bulk-upload images in parallel. Capped at 6 so a large zip cannot starve the
     * web threads or hold too many decoded photos (~50 MB each) in memory at once.
     */
    @Bean(name = "projectImageExecutor")
    public ThreadPoolTaskExecutor projectImageExecutor() {
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("project-image-");
        executor.initialize();
        return executor;
    }
}
