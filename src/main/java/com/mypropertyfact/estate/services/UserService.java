package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.dtos.CreateUserBySuperAdminResponse;
import com.mypropertyfact.estate.dtos.SuperAdminCreateUserRequest;
import com.mypropertyfact.estate.entities.MasterRole;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.repositories.AdminPasswordResetRequestRepository;
import com.mypropertyfact.estate.repositories.MasterRoleRepository;
import com.mypropertyfact.estate.repositories.PropertyListingRepository;
import com.mypropertyfact.estate.repositories.UserRepository;
import com.mypropertyfact.estate.security.AdminPermissionKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService{
    private final UserRepository userRepository;
    private final MasterRoleRepository masterRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminPermissionService adminPermissionService;
    private final UserRoleService userRoleService;
    private final PropertyListingRepository propertyListingRepository;
    private final AdminPasswordResetRequestRepository adminPasswordResetRequestRepository;

    public List<User> allUsers() {
        return userRepository.findAll();
    }

    public List<User> findPendingPortalApprovals() {
        return userRepository.findPendingPortalApprovals().stream()
                .filter(u -> !u.hasActiveSuperAdminRole())
                .toList();
    }

    public Optional<User> findById(Integer id) {
        return userRepository.findById(id);
    }

    /**
     * Super Admin creates a dashboard user (pre-approved, no public /admin/register).
     */
    @Transactional
    public CreateUserBySuperAdminResponse createUserBySuperAdmin(SuperAdminCreateUserRequest req) {
        if (!currentActorIsSuperAdmin()) {
            throw new IllegalArgumentException("Only Super Admin can create users.");
        }

        String email = req.getEmail() != null ? req.getEmail().trim() : "";
        if (email.isEmpty()) {
            throw new IllegalArgumentException("Email is required.");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException(
                    "An account with this email already exists.");
        }

        Set<MasterRole> roles = new HashSet<>();
        boolean hasAdmin = false;

        List<Integer> ids = req.getRoleIds();
        if (ids == null || ids.isEmpty()) {
            addDefaultUserRole(roles);
        } else {
            Set<Integer> seen = new HashSet<>();
            for (Integer rid : ids) {
                if (rid == null || !seen.add(rid)) {
                    continue;
                }
                MasterRole r = masterRoleRepository.findById(rid)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid role selected."));
                if (!Boolean.TRUE.equals(r.getIsActive())) {
                    throw new IllegalArgumentException("Invalid role selected.");
                }
                String n = r.getRoleName();
                if (n == null) {
                    continue;
                }
                // Only reachable by an authenticated Super Admin (checked above), so SUPERADMIN may be granted here.
                boolean isSuper = "SUPERADMIN".equalsIgnoreCase(n);
                if (!isSuper && !"USER".equalsIgnoreCase(n) && !UserRoleService.isStaffAdminRoleName(n)) {
                    throw new IllegalArgumentException("Only USER, ADMIN and SUPERADMIN roles are allowed here.");
                }
                if (UserRoleService.isStaffAdminRoleName(n)) {
                    hasAdmin = true;
                }
                roles.add(r);
            }
            if (roles.isEmpty()) {
                addDefaultUserRole(roles);
            } else if (!roles.stream().anyMatch(r -> r.getRoleName() != null
                    && "USER".equalsIgnoreCase(r.getRoleName()))) {
                addDefaultUserRole(roles);
            }
            hasAdmin = roles.stream().anyMatch(r -> UserRoleService.isStaffAdminRoleName(r.getRoleName()));
        }

        String dash = req.getDashboardUsername() != null ? req.getDashboardUsername().trim() : "";
        if (hasAdmin) {
            if (dash.isEmpty()) {
                throw new IllegalArgumentException("Dashboard username is required when Admin role is selected.");
            }
            if (userRepository.findByDashboardUsername(dash).isPresent()) {
                throw new IllegalArgumentException("That dashboard username is already taken.");
            }
        }

        String fullName = req.getFullName() != null ? req.getFullName().trim() : "";
        if (fullName.isEmpty()) {
            fullName = email.contains("@") ? email.substring(0, email.indexOf('@')) : "User";
        }

        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPhone(req.getPhone() != null && !req.getPhone().isBlank() ? req.getPhone().trim() : null);
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setDashboardUsername(hasAdmin ? dash : (dash.isEmpty() ? null : dash));
        user.setRoles(roles);
        user.setEnabled(req.getEnabled() == null || Boolean.TRUE.equals(req.getEnabled()));
        user.setVerified(req.getVerified() == null || Boolean.TRUE.equals(req.getVerified()));
        user.setAdminStaffApproved(true);
        user.setUserCategory(normalizeUserCategory(
                req.getUserCategory() != null && !req.getUserCategory().isBlank()
                        ? req.getUserCategory()
                        : "ADMIN_USER"));

        String enquiryPinPlain = null;
        if (hasAdmin) {
            Set<String> perms = adminPermissionService.normalizePermissions(req.getAdminPermissions());
            user.setAdminPermissions(perms);
            boolean wantsEnquiry = perms.stream()
                    .anyMatch(p -> p != null
                            && AdminPermissionKeys.MANAGE_ENQUIRIES.equalsIgnoreCase(p.trim()));
            if (wantsEnquiry) {
                String rawPin = req.getEnquiryAccessPin() != null ? req.getEnquiryAccessPin().trim() : "";
                if (!rawPin.matches("\\d{4}")) {
                    throw new IllegalArgumentException(
                            "Set a 4-digit enquiries access code when Manage enquiries is enabled.");
                }
                user.setEnquiryAccessPinHash(passwordEncoder.encode(rawPin));
                enquiryPinPlain = rawPin;
            }
        } else {
            user.setAdminPermissions(new HashSet<>());
        }

        User saved = userRepository.save(user);
        List<String> roleNames = roles.stream()
                .map(MasterRole::getRoleName)
                .filter(n -> n != null && !n.isBlank())
                .sorted()
                .toList();
        return new CreateUserBySuperAdminResponse(
                saved, req.getPassword(), enquiryPinPlain, roleNames);
    }

    private void addDefaultUserRole(Set<MasterRole> roles) {
        masterRoleRepository.findByRoleNameIgnoreCase("USER")
                .ifPresentOrElse(
                        roles::add,
                        () -> {
                            MasterRole userRole = new MasterRole();
                            userRole.setRoleName("USER");
                            userRole.setDescription("Default user role");
                            userRole.setIsActive(true);
                            roles.add(masterRoleRepository.save(userRole));
                        });
    }

    public User updateUser(Integer id, User updatedUser) {
        if (!currentActorIsSuperAdmin()) {
            throw new IllegalArgumentException("Only Super Admin can update users.");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));

        boolean targetIsSuperAdmin = userRoleService.userHasRole(id, "SUPERADMIN");
        
        if (updatedUser.getFullName() != null) {
            user.setFullName(updatedUser.getFullName());
        }
        if (updatedUser.getPhone() != null) {
            user.setPhone(updatedUser.getPhone());
        }
        if (updatedUser.getLocation() != null) {
            user.setLocation(updatedUser.getLocation());
        }
        if (updatedUser.getBio() != null) {
            user.setBio(updatedUser.getBio());
        }
        if (updatedUser.getAvatar() != null) {
            user.setAvatar(updatedUser.getAvatar());
        }
        if (updatedUser.getExperience() != null) {
            user.setExperience(updatedUser.getExperience());
        }
        if (updatedUser.getRating() != null) {
            user.setRating(updatedUser.getRating());
        }
        if (updatedUser.getTotalDeals() != null) {
            user.setTotalDeals(updatedUser.getTotalDeals());
        }
        if (updatedUser.getVerified() != null) {
            user.setVerified(updatedUser.getVerified());
        }
        if (updatedUser.getEnabled() != null) {
            if (targetIsSuperAdmin && Boolean.FALSE.equals(updatedUser.getEnabled())) {
                long superCount = userRepository.countDistinctUsersHavingActiveRole("SUPERADMIN");
                if (superCount <= 1) {
                    throw new IllegalArgumentException("Cannot deactivate the last Super Administrator account.");
                }
            }
            user.setEnabled(updatedUser.getEnabled());
        }
        if (updatedUser.getDashboardUsername() != null) {
            String u = updatedUser.getDashboardUsername().trim();
            user.setDashboardUsername(u.isEmpty() ? null : u);
        }
        if (updatedUser.getUserCategory() != null) {
            user.setUserCategory(normalizeUserCategory(updatedUser.getUserCategory()));
        }
        // Super Admin accounts do not use granular module permissions — ignore attempts to set them.
        if (updatedUser.getAdminPermissions() != null) {
            if (targetIsSuperAdmin) {
                throw new IllegalArgumentException(
                        "Super Admin permissions cannot be modified. Super Admin always has full access.");
            }
            if (userRoleService.userHasRole(id, "ADMIN")) {
                user.setAdminPermissions(
                        adminPermissionService.normalizePermissions(updatedUser.getAdminPermissions()));
            }
        }

        boolean wantsEnquiry = user.getAdminPermissions() != null
                && user.getAdminPermissions().stream()
                .anyMatch(p -> p != null
                        && AdminPermissionKeys.MANAGE_ENQUIRIES.equalsIgnoreCase(p.trim()));

        if (!wantsEnquiry) {
            user.setEnquiryAccessPinHash(null);
        } else {
            if (updatedUser.getEnquiryAccessPin() != null) {
                String rawPin = updatedUser.getEnquiryAccessPin().trim();
                if (!rawPin.isEmpty()) {
                    if (!rawPin.matches("\\d{4}")) {
                        throw new IllegalArgumentException("Enquiries access code must be exactly 4 digits.");
                    }
                    user.setEnquiryAccessPinHash(passwordEncoder.encode(rawPin));
                }
            }
            if (user.getEnquiryAccessPinHash() == null || user.getEnquiryAccessPinHash().isBlank()) {
                throw new IllegalArgumentException(
                        "Set a 4-digit enquiries access code when Manage enquiries is enabled.");
            }
        }

        return userRepository.save(user);
    }

    private boolean currentActorIsSuperAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User actor) || actor.getId() == null) {
            return false;
        }
        return userRoleService.userHasRole(actor.getId(), "SUPERADMIN");
    }

    private String normalizeUserCategory(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase();
        return switch (v) {
            case "TEST_USER", "APP_USER", "ADMIN_USER", "PORTAL_USER" -> v;
            default -> throw new IllegalArgumentException(
                    "Invalid user category. Allowed: TEST_USER, APP_USER, ADMIN_USER, PORTAL_USER.");
        };
    }

    public User activateUser(Integer id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    public User deactivateUser(Integer id) {
        if (userRoleService.userHasRole(id, "SUPERADMIN")) {
            long superCount = userRepository.countDistinctUsersHavingActiveRole("SUPERADMIN");
            if (superCount <= 1) {
                throw new IllegalArgumentException("Cannot deactivate the last Super Administrator account.");
            }
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        user.setEnabled(false);
        return userRepository.save(user);
    }

    /**
     * Sets a new bcrypt password for a user (super-admin only at controller layer).
     */
    public User setPasswordByAdmin(Integer id, String rawPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        user.setPassword(passwordEncoder.encode(rawPassword));
        return userRepository.save(user);
    }

    /**
     * Permanently deletes a user (Super Admin only). Clears references that would block FK constraints.
     */
    @Transactional
    public void deleteUserAsSuperAdmin(int targetUserId, int actingUserId) {
        if (targetUserId == actingUserId) {
            throw new IllegalArgumentException("You cannot delete your own account.");
        }
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        if (userRoleService.userHasRole(targetUserId, "SUPERADMIN")) {
            long superCount = userRepository.countDistinctUsersHavingActiveRole("SUPERADMIN");
            if (superCount <= 1) {
                throw new IllegalArgumentException("Cannot delete the last Super Administrator account.");
            }
        }

        if (propertyListingRepository.countByUserId(targetUserId) > 0) {
            throw new IllegalArgumentException(
                    "This user has property listing(s). Remove or transfer those listings before deleting the account.");
        }

        propertyListingRepository.clearApprovedByUserId(targetUserId);
        adminPasswordResetRequestRepository.deleteByUser_Id(targetUserId);

        userRepository.delete(target);
    }
}
