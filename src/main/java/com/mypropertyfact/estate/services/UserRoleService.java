package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.entities.MasterRole;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.repositories.MasterRoleRepository;
import com.mypropertyfact.estate.repositories.UserRepository;
import com.mypropertyfact.estate.security.AdminPermissionKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserRoleService {
    
    private final UserRepository userRepository;
    private final MasterRoleRepository masterRoleRepository;

    /** Dashboard staff Admin role (not Super Admin). Supports legacy {@code ADMINUSER} name. */
    public static boolean isStaffAdminRoleName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        String n = roleName.trim().toUpperCase(Locale.ROOT);
        if (n.contains("SUPER")) {
            return false;
        }
        return "ADMIN".equals(n) || "ADMINUSER".equals(n);
    }
    
    /**
     * Assign multiple roles to a user
     * @param userId The user ID
     * @param roleIds List of role IDs to assign
     * @return Updated user entity
     */
    @Transactional
    public User assignRolesToUser(Integer userId, List<Integer> roleIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        boolean wasSuper = userHasRole(userId, "SUPERADMIN");
        
        Set<MasterRole> roles = new HashSet<>();
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Integer roleId : roleIds) {
                masterRoleRepository.findById(roleId)
                        .ifPresent(roles::add);
            }
        }
        // The endpoint is Super Admin only, so granting SUPERADMIN is allowed. Never remove the last Super Admin.
        boolean assigningSuper = roles.stream()
                .anyMatch(r -> r != null && "SUPERADMIN".equalsIgnoreCase(r.getRoleName()));
        if (wasSuper && !assigningSuper
                && userRepository.countDistinctUsersHavingActiveRole("SUPERADMIN") <= 1) {
            throw new IllegalArgumentException("This is the only Super Admin. Make another user Super Admin first.");
        }
        user.setRoles(roles);
        boolean hasAdmin = roles.stream()
                .anyMatch(r -> r != null && Boolean.TRUE.equals(r.getIsActive())
                        && isStaffAdminRoleName(r.getRoleName()));
        if (!hasAdmin) {
            user.setAdminPermissions(new HashSet<>());
            user.setAdminStaffApproved(true);
        } else {
            if (user.getAdminPermissions() == null || user.getAdminPermissions().isEmpty()) {
                // Match create-user + legacy empty-set semantics: all CMS except enquiries.
                user.setAdminPermissions(new HashSet<>(AdminPermissionKeys.defaultStaffAdminKeys()));
            }
            if (!Boolean.FALSE.equals(user.getAdminStaffApproved())) {
                user.setAdminStaffApproved(true);
            }
        }
        return userRepository.save(user);
    }

    @Transactional
    public User approvePendingAdminStaff(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!Boolean.FALSE.equals(user.getAdminStaffApproved())) {
            throw new IllegalArgumentException("This account is not waiting for approval.");
        }
        boolean hasAdmin = user.getRoles() != null && user.getRoles().stream()
                .anyMatch(r -> r != null && Boolean.TRUE.equals(r.getIsActive())
                        && isStaffAdminRoleName(r.getRoleName()));
        if (hasAdmin) {
            if (user.getAdminPermissions() == null || user.getAdminPermissions().isEmpty()) {
                user.setAdminPermissions(new HashSet<>(AdminPermissionKeys.defaultStaffAdminKeys()));
            }
        }
        user.setAdminStaffApproved(true);
        int v = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        user.setTokenVersion(v + 1);
        return userRepository.save(user);
    }

    @Transactional
    public User rejectPendingAdminStaff(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!Boolean.FALSE.equals(user.getAdminStaffApproved())) {
            throw new IllegalArgumentException("This account is not waiting for approval.");
        }

        boolean hasAdmin = user.getRoles() != null && user.getRoles().stream()
                .anyMatch(r -> r != null && Boolean.TRUE.equals(r.getIsActive())
                        && isStaffAdminRoleName(r.getRoleName()));

        if (hasAdmin) {
            MasterRole adminMaster = masterRoleRepository.findByRoleNameIgnoreCase("ADMIN")
                    .orElseThrow(() -> new IllegalStateException("ADMIN role is not configured."));
            if (user.getRoles() != null) {
                int adminId = adminMaster.getId();
                user.getRoles().removeIf(r -> r != null && r.getId() == adminId);
            }
            user.setAdminPermissions(new HashSet<>());
            user.setDashboardUsername(null);
        } else {
            user.setEnabled(false);
        }
        user.setAdminStaffApproved(true);
        int v = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        user.setTokenVersion(v + 1);
        return userRepository.save(user);
    }
    
    /**
     * Add a single role to a user without removing existing roles
     * @param userId The user ID
     * @param roleId The role ID to add
     * @return Updated user entity
     */
    @Transactional
    public User addRoleToUser(Integer userId, Integer roleId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        masterRoleRepository.findById(roleId)
                .ifPresent(role -> {
                    Set<MasterRole> roles = user.getRoles();
                    if (roles == null) {
                        roles = new HashSet<>();
                        user.setRoles(roles);
                    }
                    roles.add(role);
                });
        
        return userRepository.save(user);
    }
    
    /**
     * Remove a single role from a user
     * @param userId The user ID
     * @param roleId The role ID to remove
     * @return Updated user entity
     */
    @Transactional
    public User removeRoleFromUser(Integer userId, Integer roleId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Set<MasterRole> roles = user.getRoles();
        if (roles != null) {
            roles.removeIf(role -> role.getId() == roleId);
            user.setRoles(roles);
        }
        
        return userRepository.save(user);
    }
    
    /**
     * Get all roles assigned to a user
     * @param userId The user ID
     * @return Set of MasterRole entities
     */
    public Set<MasterRole> getUserRoles(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getRoles();
    }
    
    /**
     * Get role names assigned to a user
     * @param userId The user ID
     * @return List of role names
     */
    public List<String> getUserRoleNames(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        // if (user.getRoles() == null || user.getRoles().isEmpty()) {
        //     // Fallback to legacy role field
        //     if (user.getRole() != null && !user.getRole().isEmpty()) {
        //         return List.of(user.getRole());
        //     }
        //     return List.of();
        // }
        
        return user.getRoles().stream()
                .filter(role -> role.getIsActive() != null && role.getIsActive())
                .map(MasterRole::getRoleName)
                .collect(Collectors.toList());
    }
    
    /**
     * Check if user has a specific role
     * @param userId The user ID
     * @param roleName The role name to check
     * @return True if user has the role, false otherwise
     */
    public boolean userHasRole(Integer userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if ("ADMIN".equalsIgnoreCase(roleName)
                && Boolean.FALSE.equals(user.getAdminStaffApproved())) {
            return false;
        }

        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            if ("ADMIN".equalsIgnoreCase(roleName)) {
                return user.getRoles().stream()
                        .anyMatch(role -> isStaffAdminRoleName(role.getRoleName())
                                && role.getIsActive() != null
                                && role.getIsActive());
            }
            return user.getRoles().stream()
                    .anyMatch(role -> roleName.equalsIgnoreCase(role.getRoleName()) 
                            && role.getIsActive() != null 
                            && role.getIsActive());
        }
        
        // Fallback to legacy role field
        // if (user.getRole() != null) {
        //     return roleName.equalsIgnoreCase(user.getRole());
        // }
        
        return false;
    }
    
    /**
     * Clear all roles from a user
     * @param userId The user ID
     * @return Updated user entity
     */
    @Transactional
    public User clearAllRoles(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        user.setRoles(new HashSet<>());
        return userRepository.save(user);
    }

    @Transactional
    public User assignPortalPersona(Integer userId, String userType) {
        String normalized = userType == null ? "" : userType.trim().toUpperCase();
        String roleName = switch (normalized) {
            case "OWNER", "PROPERTY_OWNER" -> "OWNER";
            case "BROKER" -> "BROKER";
            default -> throw new IllegalArgumentException("userType must be OWNER or BROKER");
        };

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Set<MasterRole> roles = new HashSet<>();
        masterRoleRepository.findByRoleNameIgnoreCase(roleName)
                .ifPresentOrElse(roles::add, () -> {
                    MasterRole role = new MasterRole();
                    role.setRoleName(roleName);
                    role.setDescription("Portal " + roleName.toLowerCase() + " role");
                    role.setIsActive(true);
                    roles.add(masterRoleRepository.save(role));
                });
        user.setRoles(roles);
        user.setAdminStaffApproved(true);
        return userRepository.save(user);
    }

    public String resolvePortalPersonaLabel(User user) {
        if (user.getRoles() != null) {
            for (MasterRole role : user.getRoles()) {
                if (role != null && "OWNER".equalsIgnoreCase(role.getRoleName())) {
                    return "OWNER";
                }
            }
            for (MasterRole role : user.getRoles()) {
                if (role != null && "BROKER".equalsIgnoreCase(role.getRoleName())) {
                    return "BROKER";
                }
            }
        }
        return "BROKER";
    }
}
