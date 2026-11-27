package com.mypropertyfact.estate.integration.mcpcontroller.scheduler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mypropertyfact.estate.integration.mcpcontroller.config.McpControllerProperties;
import com.mypropertyfact.estate.integration.mcpcontroller.service.McpControllerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional scheduled health probe (read-only). Only created when
 * {@code mpf.mcp-controller.scheduled-probe-enabled=true} (env MPF_MCPCONTROLLER_SCHEDULEDPROBEENABLED=true).
 * Logs PASS / ERROR only; the latest result is also visible on GET /api/v1/admin/mcp-controller/status.
 * Never validates or imports anything.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mpf.mcp-controller", name = "scheduled-probe-enabled", havingValue = "true")
public class McpControllerScheduler {

    private final McpControllerProperties props;
    private final McpControllerService service;

    @Scheduled(cron = "${mpf.mcp-controller.scheduled-probe-cron:0 30 7 * * *}")
    public void scheduledProbe() {
        if (!props.isEnabled() || !service.isConfigured()) {
            log.info("[mcp-controller] scheduled probe skipped (disabled or MPF_MCP_URL not set)");
            return;
        }
        try {
            ObjectNode report = service.probe(null);
            if (!"PASS".equals(report.path("status").asText())) {
                log.warn("[mcp-controller] scheduled probe {}: {}", report.path("status").asText(), report.path("message").asText(""));
            }
        } catch (McpControllerService.BusyException e) {
            log.info("[mcp-controller] scheduled probe skipped (another run in progress)");
        }
    }
}
