package com.mypropertyfact.estate.integration.claude.security;

import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import com.mypropertyfact.estate.security.AdminPermissionKeys;
import com.mypropertyfact.estate.services.AdminPermissionService;
import com.mypropertyfact.estate.services.UserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * Checks every connector call, on top of the existing JWT login:
 * <ol>
 *   <li>connector enabled (otherwise 404),</li>
 *   <li>authenticated (401),</li>
 *   <li>existing {@code MANAGE_PROJECTS} permission via {@link AdminPermissionService} (403),</li>
 *   <li>account email in {@code claude.integration.allowed-emails} (403),</li>
 *   <li>least privilege: not SUPERADMIN, and an ADMIN whose explicit permission rows are exactly
 *       {@code MANAGE_PROJECTS} (an ADMIN with no rows has full legacy CMS access) (403).</li>
 * </ol>
 */
@Service("claudeIntegrationAccess")
@RequiredArgsConstructor
public class ClaudeIntegrationAccessService {

    private final ClaudeIntegrationProperties props;
    private final AdminPermissionService adminPermissionService;
    private final UserRoleService userRoleService;

    public User requireIntegrationUser(Authentication authentication) {
        if (!props.isEnabled()) {
            throw new ClaudeIntegrationException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");
        }
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof User user)) {
            throw new ClaudeIntegrationException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        if (!adminPermissionService.can(user, AdminPermissionKeys.MANAGE_PROJECTS)) {
            throw new ClaudeIntegrationException(HttpStatus.FORBIDDEN, "FORBIDDEN", "MANAGE_PROJECTS permission is required");
        }
        if (!props.isAllowedEmail(user.getEmail())) {
            throw new ClaudeIntegrationException(HttpStatus.FORBIDDEN, "FORBIDDEN", "This account is not enabled for the Claude integration");
        }
        if (props.isRequireLeastPrivilegeAccount()) {
            String problem = leastPrivilegeProblem(user);
            if (problem != null) {
                throw new ClaudeIntegrationException(HttpStatus.FORBIDDEN, "ACCOUNT_TOO_PRIVILEGED", problem);
            }
        }
        return user;
    }

    /** @return null when the account is a dedicated ADMIN with exactly MANAGE_PROJECTS, else the reason. */
    public String leastPrivilegeProblem(User user) {
        if (user.getId() != null && userRoleService.userHasRole(user.getId(), "SUPERADMIN")) {
            return "The integration account must not be a Super Admin";
        }
        Set<String> perms = user.getAdminPermissions();
        if (perms == null || perms.isEmpty()) {
            return "The integration account has no explicit permissions, which the existing model treats as full CMS access. "
                    + "Grant exactly MANAGE_PROJECTS.";
        }
        Set<String> normalized = adminPermissionService.normalizePermissions(perms);
        if (!Objects.equals(normalized, Set.of(AdminPermissionKeys.MANAGE_PROJECTS))) {
            return "The integration account must have exactly MANAGE_PROJECTS (has " + normalized + ")";
        }
        return null;
    }
}
