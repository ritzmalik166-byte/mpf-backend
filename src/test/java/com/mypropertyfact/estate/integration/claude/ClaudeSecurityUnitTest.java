package com.mypropertyfact.estate.integration.claude;

import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import com.mypropertyfact.estate.integration.claude.security.ClaudeIntegrationAccessService;
import com.mypropertyfact.estate.integration.claude.security.ClaudeIntegrationAccountRestrictionFilter;
import com.mypropertyfact.estate.integration.claude.security.ClaudeRateLimiter;
import com.mypropertyfact.estate.services.AdminPermissionService;
import com.mypropertyfact.estate.services.UserRoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeSecurityUnitTest {

    private ClaudeIntegrationProperties props;
    private AdminPermissionService perms;
    private UserRoleService roles;
    private ClaudeIntegrationAccessService access;

    @BeforeEach
    void setUp() {
        props = new ClaudeIntegrationProperties();
        props.setEnabled(true);
        props.setAllowedEmails(List.of("Claude-Importer@Example.com"));
        perms = mock(AdminPermissionService.class);
        roles = mock(UserRoleService.class);
        when(perms.normalizePermissions(anyCollection())).thenAnswer(i -> new java.util.HashSet<>((java.util.Collection<String>) i.getArgument(0)));
        access = new ClaudeIntegrationAccessService(props, perms, roles);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static User user(int id, String email, Set<String> permissions) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setAdminPermissions(new java.util.HashSet<>(permissions));
        return u;
    }

    private static UsernamePasswordAuthenticationToken auth(User u) {
        return new UsernamePasswordAuthenticationToken(u, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }

    private void expect(Runnable r, HttpStatus status, String code) {
        assertThatThrownBy(r::run).isInstanceOf(ClaudeIntegrationException.class)
                .satisfies(e -> {
                    assertThat(((ClaudeIntegrationException) e).getStatus()).isEqualTo(status);
                    assertThat(((ClaudeIntegrationException) e).getCode()).isEqualTo(code);
                });
    }

    @Test
    void dedicatedLeastPrivilegeAccountIsAccepted() {
        User u = user(7, "claude-importer@example.com", Set.of("MANAGE_PROJECTS"));
        when(perms.can(u, "MANAGE_PROJECTS")).thenReturn(true);
        assertThat(access.requireIntegrationUser(auth(u))).isSameAs(u);
    }

    @Test
    void disabledConnectorAnswersNotFound() {
        props.setEnabled(false);
        User u = user(7, "claude-importer@example.com", Set.of("MANAGE_PROJECTS"));
        expect(() -> access.requireIntegrationUser(auth(u)), HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void anonymousIsUnauthorized() {
        expect(() -> access.requireIntegrationUser(null), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        expect(() -> access.requireIntegrationUser(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    void userWithoutManageProjectsIsForbidden() {
        User u = user(7, "claude-importer@example.com", Set.of("MANAGE_BLOGS"));
        when(perms.can(u, "MANAGE_PROJECTS")).thenReturn(false);
        expect(() -> access.requireIntegrationUser(auth(u)), HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    @Test
    void accountNotOnAllowListIsForbidden() {
        User u = user(8, "someone-else@example.com", Set.of("MANAGE_PROJECTS"));
        when(perms.can(u, "MANAGE_PROJECTS")).thenReturn(true);
        expect(() -> access.requireIntegrationUser(auth(u)), HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    @Test
    void overPrivilegedAccountsAreRefused() {
        User superAdmin = user(9, "claude-importer@example.com", Set.of("MANAGE_PROJECTS"));
        when(perms.can(superAdmin, "MANAGE_PROJECTS")).thenReturn(true);
        when(roles.userHasRole(9, "SUPERADMIN")).thenReturn(true);
        expect(() -> access.requireIntegrationUser(auth(superAdmin)), HttpStatus.FORBIDDEN, "ACCOUNT_TOO_PRIVILEGED");

        User legacyFull = user(10, "claude-importer@example.com", Set.of());
        when(perms.can(legacyFull, "MANAGE_PROJECTS")).thenReturn(true);
        expect(() -> access.requireIntegrationUser(auth(legacyFull)), HttpStatus.FORBIDDEN, "ACCOUNT_TOO_PRIVILEGED");

        User extra = user(11, "claude-importer@example.com", Set.of("MANAGE_PROJECTS", "MANAGE_BLOGS"));
        when(perms.can(extra, "MANAGE_PROJECTS")).thenReturn(true);
        expect(() -> access.requireIntegrationUser(auth(extra)), HttpStatus.FORBIDDEN, "ACCOUNT_TOO_PRIVILEGED");
    }

    @Test
    void rateLimiterAllowsConfiguredCallsPerMinute() {
        props.setRateLimitPerMinute(2);
        ClaudeRateLimiter limiter = new ClaudeRateLimiter(props);
        limiter.check("u1");
        limiter.check("u1");
        assertThatThrownBy(() -> limiter.check("u1")).isInstanceOf(ClaudeIntegrationException.class);
        limiter.check("u2");
    }

    @Test
    void restrictionFilterBlocksExistingWriteApisForIntegrationAccountOnly() throws Exception {
        ClaudeIntegrationAccountRestrictionFilter filter = new ClaudeIntegrationAccountRestrictionFilter(props);
        User integration = user(7, "claude-importer@example.com", Set.of("MANAGE_PROJECTS"));
        User staff = user(8, "editor@example.com", Set.of("MANAGE_PROJECTS"));

        SecurityContextHolder.getContext().setAuthentication(auth(integration));
        assertThat(run(filter, "DELETE", "/api/v1/projects/delete/5")).isEqualTo(403);
        assertThat(run(filter, "POST", "/api/v1/projects/add-new")).isEqualTo(403);
        assertThat(run(filter, "POST", "/api/v1/excel-upload/projects")).isEqualTo(403);
        assertThat(run(filter, "GET", "/api/v1/projects/admin/get-by-id/5")).isEqualTo(200);
        assertThat(run(filter, "GET", "/api/v1/projects/admin/get/the-core-ultra-wide")).isEqualTo(200);
        assertThat(run(filter, "POST", "/api/v1/integrations/claude/properties/import")).isEqualTo(200);
        assertThat(run(filter, "POST", "/api/v1/admin-portal/auth/refresh")).isEqualTo(200);

        SecurityContextHolder.getContext().setAuthentication(auth(staff));
        assertThat(run(filter, "DELETE", "/api/v1/projects/delete/5")).isEqualTo(200);

        SecurityContextHolder.clearContext();
        assertThat(run(filter, "DELETE", "/api/v1/projects/delete/5")).isEqualTo(200);
    }

    private static int run(ClaudeIntegrationAccountRestrictionFilter f, String method, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }
}
