package com.mypropertyfact.estate.integration.claude.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Settings for the Claude property connector. Every value has a safe default here, so no
 * existing {@code application*.properties} file has to change. Override with environment
 * variables (Spring relaxed binding), e.g.:
 *
 * <pre>
 * CLAUDE_INTEGRATION_ENABLED=true
 * CLAUDE_INTEGRATION_ALLOWEDEMAILS=claude-importer@example.com
 * CLAUDE_INTEGRATION_TEMPDIR=/var/mpf/claude-tmp
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "claude.integration")
public class ClaudeIntegrationProperties {

    /** Master switch. Disabled endpoints answer 404. */
    private boolean enabled = false;

    /** Only these accounts (by email, case-insensitive) may call the connector. Empty = nobody. */
    private List<String> allowedEmails = new ArrayList<>();

    /**
     * Reject callers whose account has the SUPERADMIN role, or an ADMIN with no explicit permission
     * rows (which the existing permission model treats as "all CMS permissions").
     */
    private boolean requireLeastPrivilegeAccount = true;

    /**
     * Block allow-listed integration accounts from every existing API except login/session,
     * the read-only admin project GETs and this connector. See ClaudeIntegrationAccountRestrictionFilter.
     */
    private boolean restrictIntegrationAccounts = true;

    /** Requests per minute per account on upload/validate endpoints. */
    private int rateLimitPerMinute = 10;

    /** Directory for job files (workbook + zip) while a job is pending. Defaults to java.io.tmpdir/mpf-claude. */
    private String tempDir;

    private long maxExcelBytes = 20L * 1024 * 1024;
    private long maxZipBytes = 300L * 1024 * 1024;
    private long maxExtractedBytes = 1024L * 1024 * 1024;
    private int maxZipEntries = 2000;
    private long maxImageBytes = 40L * 1024 * 1024;
    private int maxCompressionRatio = 100;
    private long maxImagePixels = 60_000_000L;
    private int minImageSide = 100;
    private int maxRows = 500;

    /** When true, a LOCATION ADVANTAGE that cannot be parsed into benefits blocks the row instead of warning. */
    private boolean strictLocationAdvantage = false;

    /** When true, rows without Desktop, Mobile and Thumbnail images are blocked instead of warned. */
    private boolean requireCoreImages = false;

    public boolean isAllowedEmail(String email) {
        if (email == null || email.isBlank() || allowedEmails == null) {
            return false;
        }
        String wanted = email.trim().toLowerCase(Locale.ROOT);
        return allowedEmails.stream()
                .filter(Objects::nonNull)
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .anyMatch(wanted::equals);
    }
}
