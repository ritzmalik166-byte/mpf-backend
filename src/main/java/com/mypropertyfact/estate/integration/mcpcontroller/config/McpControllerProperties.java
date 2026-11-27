package com.mypropertyfact.estate.integration.mcpcontroller.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the read-only MCP automation controller (ported from MPF_Java_SpringBoot_Controller).
 * Every value has a safe default, so no existing {@code application*.properties} file has to change.
 *
 * <p>The MCP URL carries a secret key, so it is NEVER put in a properties file. It is read from the
 * environment variable {@code MPF_MCP_URL} (same name as the standalone controller), or from
 * {@code mpf.mcp-controller.url} if a secret manager injects it as a property.
 *
 * <pre>
 * MPF_MCP_URL=https://&lt;host&gt;/api/property-automation/mcp/&lt;key&gt;   (http allowed only for localhost)
 * MPF_MCPCONTROLLER_ENABLED=false                                 (kill switch, default true)
 * MPF_MCPCONTROLLER_SCHEDULEDPROBEENABLED=true                    (optional health probe, default false)
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mpf.mcp-controller")
public class McpControllerProperties {

    /** Kill switch. When false, the admin endpoints answer 404 and the scheduler does nothing. */
    private boolean enabled = true;

    /** Optional. Prefer the MPF_MCP_URL environment variable. Never commit a real value. */
    private String url;

    /** Project id used by probe when none is given (the protected Metro Suites Glitz draft). */
    private int defaultPropertyId = 1394;

    /** Runs probe on a schedule and logs PASS/ERROR (no report content, no URL). Off by default. */
    private boolean scheduledProbeEnabled = false;

    /** Cron for the scheduled probe. Default: every day at 07:30 server time. */
    private String scheduledProbeCron = "0 30 7 * * *";

    /** Effective URL: property first, then the MPF_MCP_URL environment variable. */
    public String resolveUrl() {
        if (url != null && !url.isBlank()) return url.trim();
        String env = System.getenv("MPF_MCP_URL");
        return env == null ? "" : env.trim();
    }
}
