package com.mypropertyfact.estate.automation.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Disabled by default. On schedule, collects evidence into JSON and runs read-only DB screening.
 * No image downloads, imports, modifications or publishing are implemented here. */
@Component
@ConditionalOnProperty(name="mpf.discovery.enabled",havingValue="true")
public class DiscoveryScheduledRunner {
    private final Magicbricks99acresSearch search;
    private final DiscoveryScreeningJob screening;
    private final ObjectMapper mapper;
    private final Path candidatesFile;
    private final String city;

    public DiscoveryScheduledRunner(Magicbricks99acresSearch search, DiscoveryScreeningJob screening,
              ObjectMapper mapper, @Value("${mpf.discovery.screening.input:./discovery-candidates.json}") String input,
              @Value("${mpf.discovery.city:Ghaziabad}") String city) {
        this.search=search;this.screening=screening;this.mapper=mapper;this.candidatesFile=Path.of(input);this.city=city;
    }
    @Scheduled(cron="${mpf.discovery.cron:0 0 6 * * *}",zone="Asia/Kolkata")
    public synchronized void run() throws Exception {
        // Fail closed on missing credentials; no stale input is used.
        List<DiscoveryCandidate> leads=search.discover(city);
        var parent=candidatesFile.toAbsolutePath().getParent();
        if(parent!=null) Files.createDirectories(parent);
        mapper.writerWithDefaultPrettyPrinter().writeValue(candidatesFile.toFile(),leads);
        screening.screen();
    }
}
