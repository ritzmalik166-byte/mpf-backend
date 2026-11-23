package com.mypropertyfact.estate.integration.claude.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(ClaudeIntegrationProperties.class)
public class ClaudeIntegrationConfig {

    /**
     * One Claude job at a time, on its own thread, so it never competes with the admin panel's
     * {@code projectImportJobExecutor} queue.
     */
    @Bean(name = "claudeImportExecutor")
    public ThreadPoolTaskExecutor claudeImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("claude-import-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
