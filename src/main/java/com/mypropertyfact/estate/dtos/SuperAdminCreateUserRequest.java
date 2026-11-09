package com.mypropertyfact.estate.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SuperAdminCreateUserRequest {

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    private String phone;

    /** Required when the account includes the Admin role (dashboard login at /admin). */
    private String dashboardUsername;

    /**
     * Role ids (USER, ADMIN, SUPERADMIN). Only a Super Admin can call this. If empty, USER is assigned.
     */
    private List<Integer> roleIds;

    /** CMS permissions when Admin role is selected. */
    private List<String> adminPermissions;

    /** Required when {@code MANAGE_ENQUIRIES} is in adminPermissions. */
    private String enquiryAccessPin;

    /** APP_USER, ADMIN_USER, or TEST_USER. Defaults to ADMIN_USER. */
    private String userCategory;

    private Boolean enabled = true;

    private Boolean verified = true;
}
