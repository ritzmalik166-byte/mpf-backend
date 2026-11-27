package com.mypropertyfact.estate.integration.claude.security;

import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Narrows what the dedicated integration account can do on EXISTING APIs, without touching the
 * existing security configuration. {@code MANAGE_PROJECTS} is the narrowest permission key the
 * application has, and it also allows editing and deleting any project through the existing
 * project APIs. This filter blocks those for allow-listed integration accounts only.
 *
 * <p>Registered by Spring Boot as a servlet filter with default (lowest) order, so it runs after
 * the Spring Security filter chain has authenticated the request. For any request whose principal
 * is NOT an allow-listed integration account it does nothing.
 *
 * <p>Allowed for integration accounts: this connector ({@code /api/v1/integrations/claude/**}),
 * the read-only admin project GETs used for verification, and auth session/refresh/logout.
 */
@Slf4j
@Component
public class ClaudeIntegrationAccountRestrictionFilter extends OncePerRequestFilter {

    private final ClaudeIntegrationProperties props;

    private static final List<Pattern> ALLOWED_ANY_METHOD = List.of(
            Pattern.compile("^/api/v1/integrations/claude(/.*)?$"),
            Pattern.compile("^/api/v1/(admin-portal/)?auth/(login|refresh|refresh-token|logout|session)$"));

    private static final List<Pattern> ALLOWED_GET = List.of(
            Pattern.compile("^/api/v1/projects/admin/get/[^/]+$"),
            Pattern.compile("^/api/v1/projects/admin/get-by-id/[0-9]+$"));

    public ClaudeIntegrationAccountRestrictionFilter(ClaudeIntegrationProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (props.isRestrictIntegrationAccounts() && isIntegrationAccount() && !isAllowed(request)) {
            log.warn("Blocked integration account request {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":403,\"error\":\"INTEGRATION_ACCOUNT_RESTRICTED\","
                    + "\"message\":\"The Claude integration account may only use the Claude integration API and read-only project checks.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isIntegrationAccount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof User user && props.isAllowedEmail(user.getEmail());
    }

    static boolean isAllowed(String method, String path) {
        for (Pattern p : ALLOWED_ANY_METHOD) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            for (Pattern p : ALLOWED_GET) {
                if (p.matcher(path).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return isAllowed(request.getMethod(), path);
    }
}
