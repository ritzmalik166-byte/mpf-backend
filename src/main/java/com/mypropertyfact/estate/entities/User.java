package com.mypropertyfact.estate.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
@Data
@Table(name = "users")
@Entity
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String fullName;

    @Column(unique = true, length = 100)
    private String email; // Made nullable for mobile-only registration

    /** Separate display/login name for admin dashboard (Super Admin / Admin). Required at login with email+password. */
    @Column(name = "dashboard_username", unique = true, length = 100)
    private String dashboardUsername;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    // @Column(name = "role", length = 50)
    // private String role = "ROLE_USER"; // Default role - kept for backward compatibility

    @Column(name = "phone", length = 20)
    private String phone;
    
    // Many-to-Many relationship with MasterRole for multiple roles support
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<MasterRole> roles = new HashSet<>();

    /** Granular CMS permissions for {@code ADMIN}; ignored for {@code SUPERADMIN} (all access). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_admin_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission_key", length = 64)
    private Set<String> adminPermissions = new HashSet<>();

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "avatar", length = 500)
    private String avatar;

    @Column(name = "experience", length = 100)
    private String experience;

    @Column(name = "rating")
    private Double rating = 0.0;

    @Column(name = "total_deals")
    private Integer totalDeals = 0;

    @Column(name = "verified")
    private Boolean verified = false;

    @Column(name = "enabled")
    private Boolean enabled = true;

    /**
     * Segment for account source/testing used by Manage Users.
     * Allowed values: TEST_USER, APP_USER, ADMIN_USER, PORTAL_USER.
     */
    @Column(name = "user_category", length = 32)
    private String userCategory = "APP_USER";

    /** Incremented on each admin login to invalidate previous sessions (single-session for admin). */
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;

    /**
     * When false, portal self-registration ({@code /auth/admin-register}) is complete but no Super
     * Administrator has activated the account; login must be blocked until approved.
     */
    @Column(name = "admin_staff_approved", nullable = false)
    private Boolean adminStaffApproved = true;

    /** Bcrypt hash of the 4-digit enquiries PIN; only for admins with MANAGE_ENQUIRIES. */
    @JsonIgnore
    @Column(name = "enquiry_access_pin_hash", length = 255)
    private String enquiryAccessPinHash;

    /**
     * Super Admin only: optional on PUT when saving a user. Never returned in JSON.
     * Exactly four digits when set.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Transient
    private String enquiryAccessPin;

    @JsonProperty("enquiryAccessPinSet")
    public boolean isEnquiryAccessPinSet() {
        return enquiryAccessPinHash != null && !enquiryAccessPinHash.isBlank();
    }

    /**
     * Active Super Admin is never granted via self-registration; even if {@code admin_staff_approved}
     * was set false by migration or mistake, this account must stay able to sign in and manage approvals.
     */
    public boolean hasActiveSuperAdminRole() {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        return roles.stream()
                .anyMatch(r -> r != null
                        && Boolean.TRUE.equals(r.getIsActive())
                        && r.getRoleName() != null
                        && "SUPERADMIN".equalsIgnoreCase(r.getRoleName()));
    }

    /**
     * True when this user must wait for Super Admin activation after portal self-registration.
     * Super Admins are exempt (they cannot be created from the public admin register form).
     */
    public boolean needsPortalActivation() {
        return Boolean.FALSE.equals(adminStaffApproved) && !hasActiveSuperAdminRole();
    }

    /** For API/UI: mirrors {@link #needsPortalActivation()} (not the raw DB column alone). */
    @JsonProperty("portalActivationPending")
    public boolean isPortalActivationPending() {
        return needsPortalActivation();
    }

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();

        if (needsPortalActivation()) {
            return authorities;
        }

        // Add authorities from MasterRole set (multiple roles support)
        if (roles != null && !roles.isEmpty()) {
            boolean grantsAdminAlias = false;
            for (MasterRole masterRole : roles) {
                if (masterRole != null && masterRole.getIsActive() != null && masterRole.getIsActive()) {
                    if (masterRole.getRoleName() != null) {
                        String roleName = masterRole.getRoleName().trim();
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
                        if (!grantsAdminAlias
                                && !"ADMIN".equalsIgnoreCase(roleName)
                                && isLegacyStaffAdminRoleName(roleName)) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                            grantsAdminAlias = true;
                        }
                    }
                }
            }
        }
        
        // Fallback to legacy single role field for backward compatibility
        // if (authorities.isEmpty()) {
        //     String userRole = (role != null && !role.isEmpty()) ? role : "ROLE_USER";
        //     authorities.add(new SimpleGrantedAuthority(userRole));
        // }
        
        return authorities;
    }

    /** Legacy dashboard staff role names that should also grant {@code ROLE_ADMIN}. */
    private static boolean isLegacyStaffAdminRoleName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        String n = roleName.trim().toUpperCase();
        return !n.contains("SUPER") && "ADMINUSER".equals(n);
    }

    @Override
    public String getUsername() {
        return email != null && !email.isBlank() ? email : phone;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Spring Security treats {@code false} here as "User is disabled" at login.
     * DB {@code NULL} must not lock out accounts (legacy / migrated rows).
     */
    @Override
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }
}
