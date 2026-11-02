package com.mypropertyfact.estate.controllers;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.mypropertyfact.estate.dtos.AdminPasswordResetEmailCheckRequest;
import com.mypropertyfact.estate.dtos.AdminPasswordResetSubmitRequest;
import com.mypropertyfact.estate.dtos.AdminPortalRegisterRequest;
import com.mypropertyfact.estate.dtos.ForgotPasswordOtpCompleteRequest;
import com.mypropertyfact.estate.dtos.ForgotPasswordRequest;
import com.mypropertyfact.estate.dtos.RegisterSendOtpRequest;
import com.mypropertyfact.estate.dtos.RegisterVerifyOtpRequest;
import com.mypropertyfact.estate.dtos.LoginResponse;
import com.mypropertyfact.estate.dtos.LoginUserDto;
import com.mypropertyfact.estate.dtos.RegisterUserDto;
import com.mypropertyfact.estate.dtos.ResetPasswordRequest;
import com.mypropertyfact.estate.dtos.TokenRequest;
import com.mypropertyfact.estate.entities.MasterRole;
import com.mypropertyfact.estate.entities.OtpPurpose;
import com.mypropertyfact.estate.entities.PendingRegistration;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.repositories.MasterRoleRepository;
import com.mypropertyfact.estate.repositories.PendingRegistrationRepository;
import com.mypropertyfact.estate.repositories.UserRepository;
import com.mypropertyfact.estate.services.AdminPermissionService;
import com.mypropertyfact.estate.services.AdminPasswordResetRequestService;
import com.mypropertyfact.estate.services.AuthenticationService;
import com.mypropertyfact.estate.services.EnquiryAccessService;
import com.mypropertyfact.estate.security.AdminPermissionKeys;
import com.mypropertyfact.estate.services.JwtService;
import com.mypropertyfact.estate.services.OTPService;
import com.mypropertyfact.estate.services.SendEmailHandler;
import com.mypropertyfact.estate.services.UserRoleService;
import com.mypropertyfact.estate.services.WebsiteLoginService;
import com.mypropertyfact.estate.validation.ConsumerEmailNormalizer;
import com.mypropertyfact.estate.validation.PhoneNormalizer;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor 
public class AuthHubDelegate {

    @Value("${google.client.id}")
    private String googleClientId;

    @Value("${spring.profiles.active:}")
    private String activeProfile;
    private final JwtService jwtService;
    private final AuthenticationService authenticationService;
    private final UserRoleService userRoleService;
    private final UserRepository userRepository;
    private final MasterRoleRepository masterRoleRepository;
    private final OTPService otpService;
    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final PasswordEncoder passwordEncoder;
    private final SendEmailHandler sendEmailHandler;
    private final AdminPermissionService adminPermissionService;
    private final EnquiryAccessService enquiryAccessService;
    private final AdminPasswordResetRequestService adminPasswordResetRequestService;
    private final WebsiteLoginService websiteLoginService;

    @Value("${cookies.domain:}")
    private String cookiesDomain;

    @Value("${http.secure}")
    private boolean httpSecure;

    @Value("${security.jwt.expiration-time}")
    private long accessTokenExpiration;
    @Value("${security.jwt.refresh.expiration-time}")
    private long refreshTokenExpiration;

    @Value("${broker.auth.internal-secret:}")
    private String brokerAuthInternalSecret;

    public ResponseEntity<?> register(RegisterUserDto registerUserDto,
            HttpServletResponse response) {
        final User registeredUser;
        try {
            registeredUser = authenticationService.signup(registerUserDto);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
        }

        String jwtToken = jwtService.generateToken(registeredUser);
        String refreshToken = jwtService.generateRefreshToken(registeredUser);
        response.addHeader("Set-Cookie",
                buildAuthCookie("token", jwtToken, accessTokenExpiration / 1000).toString());
        response.addHeader("Set-Cookie",
                buildAuthCookie("refreshToken", refreshToken, refreshTokenExpiration / 1000).toString());

        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setToken(jwtToken);
        loginResponse.setRefreshToken(refreshToken);
        loginResponse.setExpiresIn(jwtService.getExpirationTime());
        loginResponse.setUser(registeredUser);
        return ResponseEntity.ok(loginResponse);
    }

    /** Consumer/mobile: request password reset email (always 200 {@code ok: true} to avoid email enumeration). */
    public ResponseEntity<Map<String, Object>> forgotPassword( ForgotPasswordRequest body) {
        authenticationService.sendConsumerPasswordResetEmail(body.getEmail());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Consumer/mobile: submit token from reset link and set a new password. */
    public ResponseEntity<?> resetPassword( ResetPasswordRequest body) {
        try {
            authenticationService.resetPasswordWithConsumerToken(body.getToken(), body.getNewPassword());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    /** Public self-registration is disabled; Super Admin creates users from Manage Users. */
    public ResponseEntity<Map<String, Object>> adminRegisterMeta() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "message",
                        "Public registration is disabled. Ask your Super Administrator to create your account."));
    }

    /** Public self-registration is disabled; Super Admin creates users from Manage Users. */
    public ResponseEntity<?> adminRegister(AdminPortalRegisterRequest body) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "message",
                        "Public registration is disabled. Ask your Super Administrator to create your account."));
    }

    /**
     * Forgot-password step 1: verify the email exists and is eligible for admin password reset.
     */
    public ResponseEntity<?> checkAdminPasswordResetEmail(
            AdminPasswordResetEmailCheckRequest body) {
        try {
            adminPasswordResetRequestService.checkEmailEligibleForPasswordReset(body.getEmail());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
        }
    }

    /**
     * Admin dashboard forgot-password: stores a bcrypt hash for super-admin review (no plaintext persisted).
     */
    public ResponseEntity<?> requestAdminPasswordReset( AdminPasswordResetSubmitRequest body) {
        try {
            adminPasswordResetRequestService.submitRequest(body);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Request recorded. A Super Administrator will review your new password."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
        }
    }

    public ResponseEntity<?> authenticate(LoginUserDto loginUserDto,
            HttpServletResponse response) {
        final User authenticatedUser;
        try {
            authenticatedUser = authenticationService.authenticate(loginUserDto);
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", ex.getMessage()));
        }
        String jwtToken = jwtService.generateToken(authenticatedUser);
        String refreshToken = jwtService.generateRefreshToken(authenticatedUser);
        response.addHeader("Set-Cookie", buildAuthCookie("token", jwtToken, accessTokenExpiration / 1000).toString());
        response.addHeader("Set-Cookie",
                buildAuthCookie("refreshToken", refreshToken, refreshTokenExpiration / 1000).toString());
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setToken(jwtToken);
        loginResponse.setRefreshToken(refreshToken);
        loginResponse.setExpiresIn(jwtService.getExpirationTime());
        loginResponse.setUser(authenticatedUser);
        return ResponseEntity.ok(loginResponse);
    }

    public ResponseEntity<?> googleLogin(TokenRequest tokenRequest,
        HttpServletResponse response ) throws Exception {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance()).setAudience(Collections.singletonList(googleClientId)).build();

        GoogleIdToken idToken = verifier.verify(tokenRequest.getToken());

        if (idToken != null) {
            GoogleIdToken.Payload payload = idToken.getPayload();

            String email = payload.getEmail();
            String name = (String) payload.get("name");
            User user;
            Optional<User> existingUser = userRepository.findByEmail(email);
            if (existingUser.isPresent()) {
                // User already registered
                user = existingUser.get();
            } else {
                // Register new user
                RegisterUserDto registerUserDto = new RegisterUserDto();
                registerUserDto.setEmail(email);
                // Ensure fullName is not null - use email prefix or default if name is not
                // available
                String userFullName = (name != null && !name.trim().isEmpty())
                        ? name.trim()
                        : (email != null ? email.split("@")[0] : "User");
                registerUserDto.setFullName(userFullName);
                registerUserDto.setPassword(UUID.randomUUID().toString()); // random password since using Google login
                String portalRole = resolvePortalRoleName(tokenRequest.getUserType());
                registerUserDto.setRole(portalRole);
                user = authenticationService.signupWithoutPassword(registerUserDto);
            }
            String jwtToken = jwtService.generateToken(user);
            String refreshToken = jwtService.generateRefreshToken(user);
            response.addHeader("Set-Cookie", buildAuthCookie("token", jwtToken, accessTokenExpiration / 1000).toString());
            response.addHeader("Set-Cookie",
                    buildAuthCookie("refreshToken", refreshToken, refreshTokenExpiration / 1000).toString());
            LoginResponse loginResponse = new LoginResponse();
            loginResponse.setToken(jwtToken);
            loginResponse.setRefreshToken(refreshToken);
            loginResponse.setExpiresIn(jwtService.getExpirationTime());
            loginResponse.setUser(user);
            return ResponseEntity.ok(loginResponse);
        } else {
            throw new RuntimeException("Invalid Google token");
        }
    }

    public ResponseEntity<?> verifyToken(String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid Authorization header");
            }

            String token = authHeader.substring(7); // remove "Bearer "

            // Verify token
            Claims claims = jwtService.validateToken(token); // custom util (explained below)

            // Extract roles from token
            Set<String> roles = jwtService.extractRoles(token);
            List<String> rolesList = new ArrayList<>(roles);

            Map<String, Object> response = new HashMap<>();
            response.put("valid", true);
            response.put("email", claims.getSubject());
            response.put("expiresAt", claims.getExpiration().toString());
            response.put("roles", rolesList);

            return ResponseEntity.ok().body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("valid", false));
        }
    }

    public ResponseEntity<?> refreshToken(Map<String, String> request) {
        String refreshToken = request.get("refreshToken");

        if (refreshToken == null || refreshToken.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Refresh token is required"));
        }

        try {
            Claims claims = jwtService.validateToken(refreshToken);
            String username = claims.getSubject();
            String emailKey = username == null ? "" : username.trim();

            Optional<User> userDetails = userRepository.findByEmailIgnoreCase(emailKey);

            if (!userDetails.isPresent()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "User not found"));
            }

            User user = userDetails.get();

            if (user.needsPortalActivation()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of(
                                "error", "pending_approval",
                                "message", "Your account has not been activated yet. Contact a Super Administrator."));
            }

            // Generate new access + refresh tokens
            String jwtToken = jwtService.generateToken(user);
            String refToken = jwtService.generateRefreshToken(user);

            LoginResponse loginResponse = new LoginResponse();
            loginResponse.setToken(jwtToken);
            loginResponse.setRefreshToken(refToken);
            loginResponse.setExpiresIn(jwtService.getExpirationTime());
            loginResponse.setUser(user);

            return ResponseEntity.ok(loginResponse);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Refresh token has expired"));
        } catch (io.jsonwebtoken.MalformedJwtException | io.jsonwebtoken.security.SignatureException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid refresh token"));
        } catch (Exception e) {
            log.error("Error refreshing token: ", e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Failed to refresh token"));
        }
    }

    /**
     * Send OTP to the user's email (consumer site / mobile app).
     * POST /app/auth/send-otp or POST /auth/send-otp
     * Body: {@code { "email": "user@example.com" }}
     */
    public ResponseEntity<?> sendOTP(Map<String, String> request) {
        try {
            String email = request.get("email");

            if (email == null || email.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email is required", "message", "Please enter your email"));
            }

            String canonicalEmail = ConsumerEmailNormalizer.normalize(email);
            boolean userExists = userRepository.findByEmailIgnoreCase(canonicalEmail).isPresent();

            // Generate and send OTP (validation + normalization inside OTPService)
            String otpCode = otpService.generateOTP(email);
            String body = buildBrandedOtpEmail(
                    otpCode,
                    "Your sign-in code",
                    """
                            Enter this One-Time Password (OTP) in the app or website to continue \
                            securely.<br><br>\
                            If you did not request this code, you can safely ignore this email.""");
            if (!sendEmailHandler.sendEmail(canonicalEmail, "Your My Property Fact sign-in code", body)) {
                return otpEmailDeliveryFailed();
            }
            return otpSentResponse(otpCode, userExists);

        } catch (IllegalArgumentException e) {
            // User-friendly validation errors
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage(), "message", e.getMessage()));
        } catch (Exception e) {
            // Check for database/data truncation errors
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("Data truncation")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid email format",
                                "message", "Please enter a valid email address."));
            }
            // Generic error message
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send OTP",
                            "message", "Unable to send OTP. Please check your email address and try again."));
        }
    }

    /**
     * Verify OTP and register or sign in an app user by email.
     * POST /app/auth/verify-otp or POST /auth/verify-otp
     * Body: {@code { "email": "...", "otp": "123456", "fullName": "..." }}
     * {@code fullName} is required only when creating a new account (first-time registration).
     */
    public ResponseEntity<?> verifyOTPAndRegister(Map<String, String> request) {
        try {
            String email = request.get("email");
            String otpCode = request.get("otp");
            String fullName = request.get("fullName");

            if (email == null || email.isEmpty() ||
                    otpCode == null || otpCode.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email and OTP are required",
                                "message", "Please enter both email and OTP"));
            }

            final String canonicalEmail;
            try {
                canonicalEmail = ConsumerEmailNormalizer.normalize(email);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", e.getMessage(), "message", e.getMessage()));
            }

            Optional<User> existingUser = userRepository.findByEmailIgnoreCase(canonicalEmail);
            boolean isNewUser = existingUser.isEmpty();

            if (isNewUser && (fullName == null || fullName.trim().isEmpty())) {
                if (!otpService.isValidOTP(canonicalEmail, otpCode, OtpPurpose.MAGIC_LINK)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "Invalid or expired OTP",
                                    "message",
                                    "The OTP you entered is incorrect or has expired. Please request a new OTP."));
                }
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error", "full_name_required",
                                "message",
                                "Full name is required to create your account."));
            }

            boolean isValid = otpService.verifyOTP(canonicalEmail, otpCode, OtpPurpose.MAGIC_LINK);

            if (!isValid) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Invalid or expired OTP",
                                "message",
                                "The OTP you entered is incorrect or has expired. Please request a new OTP."));
            }

            User user;
            String userStatus;

            if (existingUser.isPresent()) {
                user = existingUser.get();
                userStatus = "existing";
            } else {
                user = new User();
                user.setId(null);
                user.setEmail(canonicalEmail);
                user.setFullName(fullName.trim());
                // Generate a secure random password
                String randomPassword = UUID.randomUUID().toString();
                user.setPassword(passwordEncoder.encode(randomPassword));

                // Set portal role (BROKER / OWNER) for new users
                Set<MasterRole> roles = new HashSet<>();
                String portalRole = resolvePortalRoleName(request.get("userType"));
                masterRoleRepository.findByRoleNameIgnoreCase(portalRole)
                        .ifPresentOrElse(
                                roles::add,
                                () -> {
                                    MasterRole userRole = new MasterRole();
                                    userRole.setRoleName(portalRole);
                                    userRole.setDescription("Portal " + portalRole.toLowerCase() + " role");
                                    userRole.setIsActive(true);
                                    roles.add(masterRoleRepository.save(userRole));
                                });
                user.setRoles(roles);
                user.setVerified(true); // Verified via OTP

                user = userRepository.save(user);
                userStatus = "new";
            }

            // Generate JWT token
            String jwtToken = jwtService.generateToken(user);
            String refreshToken = jwtService.generateRefreshToken(user);

            LoginResponse loginResponse = new LoginResponse();
            loginResponse.setToken(jwtToken);
            loginResponse.setRefreshToken(refreshToken);
            loginResponse.setExpiresIn(jwtService.getExpirationTime());

            // Return response
            Map<String, Object> response = new HashMap<>();
            response.put("status", userStatus);
            response.put("token", jwtToken);
            response.put("refreshToken", refreshToken);
            response.put("expiresIn", jwtService.getExpirationTime());
            // Get role names from MasterRole entities
            List<String> roleNames = user.getRoles() != null
                    ? user.getRoles().stream()
                            .filter(role -> role != null && role.getIsActive() != null && role.getIsActive())
                            .map(role -> "ROLE_" + role.getRoleName())
                            .toList()
                    : List.of("ROLE_USER");

            // Use HashMap instead of Map.of() to handle null values (phone can be null for
            // email-based registration)
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("id", user.getId());
            userMap.put("fullName", user.getFullName() != null ? user.getFullName() : "");
            userMap.put("phone", user.getPhone() != null ? user.getPhone() : "");
            userMap.put("email", user.getEmail() != null ? user.getEmail() : "");
            userMap.put("role", roleNames.isEmpty() ? "ROLE_USER" : roleNames.get(0));
            userMap.put("roles", roleNames);
            userMap.put("userType", resolveUserTypeLabel(user));
            userMap.put("verified", user.getVerified() != null ? user.getVerified() : false);
            response.put("user", userMap);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // Get the root cause of the exception
            Throwable rootCause = e;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }

            String errorMessage = rootCause.getMessage();
            String userFriendlyMessage;

            // Check for specific database errors and provide user-friendly messages
            if (errorMessage != null) {
                if (errorMessage.contains("Field 'id' doesn't have a default value") ||
                        errorMessage.contains("doesn't have a default value")) {
                    userFriendlyMessage = "Unable to create your account. Please contact support or try again later.";
                } else if (errorMessage.contains("Data truncation")) {
                    userFriendlyMessage = "Invalid data provided. Please check your information and try again.";
                } else if (errorMessage.contains("Duplicate entry") || errorMessage.contains("already exists")) {
                    userFriendlyMessage = "An account with this email already exists. Please sign in instead.";
                } else if (errorMessage.contains("ConstraintViolationException") ||
                        errorMessage.contains("constraint")) {
                    userFriendlyMessage = "Invalid information provided. Please check your details and try again.";
                } else {
                    // Generic user-friendly message for other errors
                    userFriendlyMessage = "Unable to complete your registration. Please try again or contact support if the problem persists.";
                }
            } else {
                userFriendlyMessage = "Unable to complete your registration. Please try again or contact support if the problem persists.";
            }

            // Log the actual error for debugging (but don't expose it to users)
            // Note: This is a database configuration issue - the users table id column
            // needs AUTO_INCREMENT
            System.err.println("Error verifying OTP: " + errorMessage);
            e.printStackTrace();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Registration failed",
                            "message", userFriendlyMessage));
        }
    }

    /**
     * Trusted portal registration/login after phone OTP verified by Next.js (server-side).
     * POST /app/auth/phone/register
     */
    @Transactional
    public ResponseEntity<?> registerPortalUserTrusted(Map<String, String> request, String internalSecret) {
        if (brokerAuthInternalSecret == null || brokerAuthInternalSecret.isBlank()
                || internalSecret == null || !brokerAuthInternalSecret.equals(internalSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }
        request.remove("internalSecret");
        return verifyPhoneOTPAndRegisterTrusted(request);
    }

    /**
     * Trusted portal login after phone OTP verified by Next.js.
     * Does not create an account if the phone is not registered.
     * POST /app/auth/phone/login
     */
    @Transactional
    public ResponseEntity<?> loginPortalUserTrusted(Map<String, String> request, String internalSecret) {
        if (brokerAuthInternalSecret == null || brokerAuthInternalSecret.isBlank()
                || internalSecret == null || !brokerAuthInternalSecret.equals(internalSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }
        request.remove("internalSecret");
        return verifyPhoneOTPAndLoginTrusted(request);
    }

    /**
     * Trusted broker portal OTP sender.
     * Next.js calls this to generate and store the OTP; Next.js then delivers the OTP via SMS provider.
     *
     * POST /app/auth/phone/send-otp
     */
    public ResponseEntity<?> sendPhoneOTP(Map<String, String> request, String internalSecret) {
        if (brokerAuthInternalSecret == null || brokerAuthInternalSecret.isBlank()
                || internalSecret == null || !brokerAuthInternalSecret.equals(internalSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        request.remove("internalSecret");

        String phoneRaw = request.get("phone");
        if (phoneRaw == null || phoneRaw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Phone number is required"));
        }

        String phone = PhoneNormalizer.normalize(phoneRaw);
        String otpCode = otpService.generatePhoneOTP(phone, OtpPurpose.PHONE_PORTAL_LOGIN);

        // Return otpCode to trusted caller (Next.js API route) so it can send via SMS.
        return ResponseEntity.ok(
                Map.of(
                        "success", true,
                        "message", "OTP generated",
                        "expiresIn", 300,
                        "otp", otpCode));
    }

    /**
     * Public website OTP login/register (not the broker portal).
     * Existing phone → sign in. New phone → create APP_USER after name is provided.
     */
    @Transactional
    public ResponseEntity<?> loginOrRegisterWebsiteUserTrusted(
            Map<String, String> request, String internalSecret, HttpServletRequest httpRequest) {
        if (brokerAuthInternalSecret == null || brokerAuthInternalSecret.isBlank()
                || internalSecret == null || !brokerAuthInternalSecret.equals(internalSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }
        request.remove("internalSecret");

        try {
            String phoneRaw = request.get("phone");
            String otpCode = request.get("otp");
            String fullName = request.get("fullName");

            if (phoneRaw == null || phoneRaw.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Phone number is required"));
            }
            if (otpCode == null || otpCode.isBlank() || !otpCode.trim().matches("\\d{4}")) {
                return ResponseEntity.badRequest().body(Map.of("message", "Enter a valid 4-digit OTP"));
            }

            String phone = PhoneNormalizer.normalize(phoneRaw);
            if (!otpService.isValidPhoneOTP(phone, otpCode, OtpPurpose.PHONE_PORTAL_LOGIN)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "OTP expired or not sent. Please request a new code."));
            }

            Optional<User> existingByPhone = userRepository.findByPhone(phone);
            String normalizedFullName = fullName == null ? "" : fullName.trim();
            if (existingByPhone.isEmpty() && normalizedFullName.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "full_name_required",
                        "message", "Enter your name to create your account."));
            }

            boolean otpValid = otpService.verifyPhoneOTP(phone, otpCode, OtpPurpose.PHONE_PORTAL_LOGIN);
            if (!otpValid) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "OTP expired or not sent. Please request a new code."));
            }

            User user;
            String userStatus;
            if (existingByPhone.isPresent()) {
                user = existingByPhone.get();
                if (fullName != null && !fullName.trim().isEmpty()
                        && (user.getFullName() == null || user.getFullName().isBlank())) {
                    user.setFullName(normalizedFullName);
                }
                user.setVerified(true);
                user = userRepository.save(user);
                userStatus = "existing";
            } else {
                user = new User();
                user.setPhone(phone);
                user.setFullName(normalizedFullName);
                user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
                Set<MasterRole> roles = new HashSet<>();
                masterRoleRepository.findByRoleNameIgnoreCase("USER").ifPresentOrElse(roles::add, () -> {
                    MasterRole userRole = new MasterRole();
                    userRole.setRoleName("USER");
                    userRole.setDescription("Website member");
                    userRole.setIsActive(true);
                    roles.add(masterRoleRepository.save(userRole));
                });
                user.setRoles(roles);
                user.setVerified(true);
                user.setEnabled(true);
                user.setUserCategory("APP_USER");
                user = userRepository.saveAndFlush(user);
                userStatus = "new";
            }

            websiteLoginService.record(user, httpRequest, "website-otp", userStatus);
            return buildPortalAuthResponse(user, userStatus);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("loginOrRegisterWebsiteUserTrusted failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to complete sign-in. Please try again."));
        }
    }

    private ResponseEntity<?> verifyPhoneOTPAndLoginTrusted(Map<String, String> request) {
        try {
            String phoneRaw = request.get("phone");
            String otpCode = request.get("otp");

            if (phoneRaw == null || phoneRaw.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Phone number is required"));
            }

            String phone = PhoneNormalizer.normalize(phoneRaw);

            if (otpCode == null || otpCode.isBlank() || !otpCode.trim().matches("\\d{4}")) {
                return ResponseEntity.badRequest().body(Map.of("message", "Enter a valid 4-digit OTP"));
            }

            boolean otpValid = otpService.verifyPhoneOTP(phone, otpCode, OtpPurpose.PHONE_PORTAL_LOGIN);
            if (!otpValid) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "OTP expired or not sent. Please request a new code."));
            }

            Optional<User> existingByPhone = userRepository.findByPhone(phone);
            if (existingByPhone.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "success", false,
                                "error", "account_not_found",
                                "message", "Account not created with that number"));
            }

            User user = existingByPhone.get();
            user.setVerified(true);
            user = userRepository.save(user);
            return buildPortalAuthResponse(user, "existing");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("verifyPhoneOTPAndLoginTrusted failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to complete sign-in. Please try again."));
        }
    }

    private ResponseEntity<?> verifyPhoneOTPAndRegisterTrusted(Map<String, String> request) {
        try {
            String phoneRaw = request.get("phone");
            String fullName = request.get("fullName");
            String emailRaw = request.get("email");
            String otpCode = request.get("otp");

            if (phoneRaw == null || phoneRaw.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Phone number is required"));
            }

            String phone = PhoneNormalizer.normalize(phoneRaw);

            if (otpCode == null || otpCode.isBlank() || !otpCode.trim().matches("\\d{4}")) {
                return ResponseEntity.badRequest().body(Map.of("message", "Enter a valid 4-digit OTP"));
            }

            boolean otpValid = otpService.verifyPhoneOTP(phone, otpCode, OtpPurpose.PHONE_PORTAL_LOGIN);
            if (!otpValid) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "OTP expired or not sent. Please request a new code."));
            }

            Optional<User> existingByPhone = userRepository.findByPhone(phone);

            if (existingByPhone.isEmpty()) {
                if (fullName == null || fullName.trim().isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "full_name_required",
                                    "message", "Full name is required to create your account."));
                }
                if (emailRaw == null || emailRaw.trim().isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "email_required",
                                    "message", "Email is required to create your account."));
                }
            }

            String canonicalEmail = null;
            if (emailRaw != null && !emailRaw.trim().isEmpty()) {
                canonicalEmail = ConsumerEmailNormalizer.normalize(emailRaw);
                Optional<User> existingByEmail = userRepository.findByEmailIgnoreCase(canonicalEmail);
                if (existingByEmail.isPresent()
                        && (existingByPhone.isEmpty()
                                || !existingByEmail.get().getId().equals(existingByPhone.get().getId()))) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("message", "This email is already registered with another account."));
                }
            }

            User user;
            String userStatus;

            if (existingByPhone.isPresent()) {
                user = existingByPhone.get();
                if (fullName != null && !fullName.trim().isEmpty()) {
                    user.setFullName(fullName.trim());
                }
                if (canonicalEmail != null) {
                    user.setEmail(canonicalEmail);
                }
                user.setVerified(true);
                user = userRepository.save(user);
                userStatus = "existing";
            } else {
                user = new User();
                user.setPhone(phone);
                user.setFullName(fullName.trim());
                user.setEmail(canonicalEmail);
                user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));

                Set<MasterRole> roles = new HashSet<>();
                String portalRole = resolvePortalRoleName(request.get("userType"));
                masterRoleRepository.findByRoleNameIgnoreCase(portalRole).ifPresentOrElse(roles::add, () -> {
                    MasterRole userRole = new MasterRole();
                    userRole.setRoleName(portalRole);
                    userRole.setDescription("Portal " + portalRole.toLowerCase() + " role");
                    userRole.setIsActive(true);
                    roles.add(masterRoleRepository.save(userRole));
                });
                user.setRoles(roles);
                user.setVerified(true);
                user.setEnabled(true);
                user.setUserCategory("PORTAL_USER");
                user = userRepository.save(user);
                userStatus = "new";
            }

            return buildPortalAuthResponse(user, userStatus);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("verifyPhoneOTPAndRegisterTrusted failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to complete sign-in. Please try again."));
        }
    }

    private ResponseEntity<?> buildPortalAuthResponse(User user, String userStatus) {
        String jwtToken = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        Map<String, Object> response = new HashMap<>();
        response.put("status", userStatus);
        response.put("token", jwtToken);
        response.put("refreshToken", refreshToken);
        response.put("expiresIn", jwtService.getExpirationTime());

        List<String> roleNames = user.getRoles() != null
                ? user.getRoles().stream()
                        .filter(role -> role != null && role.getIsActive() != null && role.getIsActive())
                        .map(role -> "ROLE_" + role.getRoleName())
                        .toList()
                : List.of("ROLE_USER");

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("fullName", user.getFullName() != null ? user.getFullName() : "");
        userMap.put("phone", user.getPhone() != null ? user.getPhone() : "");
        userMap.put("email", user.getEmail() != null ? user.getEmail() : "");
        userMap.put("role", roleNames.isEmpty() ? "ROLE_USER" : roleNames.get(0));
        userMap.put("roles", roleNames);
        userMap.put("userType", resolveUserTypeLabel(user));
        userMap.put("verified", user.getVerified() != null ? user.getVerified() : false);
        response.put("user", userMap);

        return ResponseEntity.ok(response);
    }

    /**
     * Mobile registration step 1: store bcrypt password server-side pending OTP verification.
     */
    @Transactional
    public ResponseEntity<?> sendRegistrationOtp(RegisterSendOtpRequest req) {
        try {
            String canonicalEmail = ConsumerEmailNormalizer.normalize(req.getEmail());
            if (userRepository.findByEmailIgnoreCase(canonicalEmail).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "An account with this email already exists. Please sign in instead."));
            }

            pendingRegistrationRepository.deleteByEmail(canonicalEmail);
            PendingRegistration pending = new PendingRegistration();
            pending.setEmail(canonicalEmail);
            pending.setPasswordHash(passwordEncoder.encode(req.getPassword()));
            pending.setFullName(req.getFullName().trim());
            pendingRegistrationRepository.save(pending);

            String otpCode = otpService.generateOTP(canonicalEmail, OtpPurpose.REGISTRATION);
            String body = buildBrandedOtpEmail(
                    otpCode,
                    "Verify your email",
                    """
                            You're one step away from joining My Property Fact. Enter this code \
                            to confirm your email and finish creating your account.""");
            if (!sendEmailHandler.sendEmail(canonicalEmail, "Verify your My Property Fact registration", body)) {
                return otpEmailDeliveryFailed();
            }

            return otpSentResponse(otpCode);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("sendRegistrationOtp failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to send OTP. Please try again."));
        }
    }

    /**
     * Mobile registration step 2: OTP must match; creates account with password chosen in step 1.
     */
    @Transactional
    public ResponseEntity<?> verifyRegistrationOtp(RegisterVerifyOtpRequest req,
            HttpServletResponse response) {
        try {
            String canonicalEmail = ConsumerEmailNormalizer.normalize(req.getEmail());

            if (!otpService.verifyOTP(canonicalEmail, req.getOtp(), OtpPurpose.REGISTRATION)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Incorrect OTP. Registration failed."));
            }

            PendingRegistration pending = pendingRegistrationRepository.findByEmail(canonicalEmail)
                    .orElse(null);
            if (pending == null || pending.getExpiresAt().before(new Date())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message",
                        "Registration session expired. Please enter your details again and request a new OTP."));
            }

            User registeredUser = authenticationService.finalizeMobileRegistrationWithEncodedPassword(
                    canonicalEmail, pending.getPasswordHash(), pending.getFullName());
            pendingRegistrationRepository.deleteByEmail(canonicalEmail);

            String jwtToken = jwtService.generateToken(registeredUser);
            String refreshToken = jwtService.generateRefreshToken(registeredUser);
            response.addHeader("Set-Cookie",
                    buildAuthCookie("token", jwtToken, accessTokenExpiration / 1000).toString());
            response.addHeader("Set-Cookie",
                    buildAuthCookie("refreshToken", refreshToken, refreshTokenExpiration / 1000).toString());

            LoginResponse loginResponse = new LoginResponse();
            loginResponse.setToken(jwtToken);
            loginResponse.setRefreshToken(refreshToken);
            loginResponse.setExpiresIn(jwtService.getExpirationTime());
            loginResponse.setUser(registeredUser);
            return ResponseEntity.ok(loginResponse);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception e) {
            log.error("verifyRegistrationOtp failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Registration failed. Please try again."));
        }
    }

    /**
     * Forgot password step 1: email must exist or returns {@code Email ID does not exist.}
     */
    public ResponseEntity<?> forgotPasswordSendOtp(ForgotPasswordRequest body) {
        try {
            String raw = body.getEmail();
            if (raw == null || raw.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Email is required."));
            }
            String canonicalEmail = ConsumerEmailNormalizer.normalize(raw);

            if (userRepository.findByEmailIgnoreCase(canonicalEmail).isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Email ID does not exist."));
            }

            String otpCode = otpService.generateOTP(canonicalEmail, OtpPurpose.PASSWORD_RESET);
            String html = buildBrandedOtpEmail(
                    otpCode,
                    "Reset your password",
                    """
                            We received a request to reset your password. Enter this code to verify \
                            it's you before choosing a new password.<br><br>\
                            If you didn't ask for this, you can ignore this email.""");
            if (!sendEmailHandler.sendEmail(canonicalEmail, "Reset your My Property Fact password", html)) {
                return otpEmailDeliveryFailed();
            }

            return otpSentResponse(otpCode);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("forgotPasswordSendOtp failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to send OTP. Please try again."));
        }
    }

    /**
     * Forgot password step 2: verify OTP then set new password.
     */
    @Transactional
    public ResponseEntity<?> forgotPasswordCompleteWithOtp(ForgotPasswordOtpCompleteRequest body) {
        try {
            String canonicalEmail = ConsumerEmailNormalizer.normalize(body.getEmail());

            if (!otpService.verifyOTP(canonicalEmail, body.getOtp(), OtpPurpose.PASSWORD_RESET)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Incorrect or expired OTP. Please request a new OTP."));
            }

            User user = userRepository.findByEmailIgnoreCase(canonicalEmail).orElseThrow();
            authenticationService.replaceConsumerPassword(user, body.getNewPassword());

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "Password updated successfully. You can sign in with your new password."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("forgotPasswordCompleteWithOtp failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to reset password. Please try again."));
        }
    }

    private boolean isDevProfile() {
        return activeProfile != null && activeProfile.contains("dev");
    }

    /** Normalize portal persona from client request to MasterRole name. */
    private String resolvePortalRoleName(String userType) {
        if (userType == null || userType.isBlank()) {
            return "BROKER";
        }
        String normalized = userType.trim().toUpperCase(Locale.ROOT)
                .replace("ROLE_", "");
        return switch (normalized) {
            case "OWNER", "PROPERTY_OWNER" -> "OWNER";
            case "BROKER" -> "BROKER";
            default -> "BROKER";
        };
    }

    /** Derive display label for portal persona from assigned roles. */
    private String resolveUserTypeLabel(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            return "BROKER";
        }
        for (MasterRole role : user.getRoles()) {
            if (role == null || role.getRoleName() == null) continue;
            String name = role.getRoleName().toUpperCase(Locale.ROOT);
            if ("OWNER".equals(name) || "PROPERTY_OWNER".equals(name)) {
                return "OWNER";
            }
        }
        for (MasterRole role : user.getRoles()) {
            if (role != null && "BROKER".equalsIgnoreCase(role.getRoleName())) {
                return "BROKER";
            }
        }
        for (MasterRole role : user.getRoles()) {
            if (role != null && "USER".equalsIgnoreCase(role.getRoleName())) {
                return "USER";
            }
        }
        return "BROKER";
    }

    private ResponseEntity<Map<String, Object>> otpSentResponse(String otpCode) {
        return otpSentResponse(otpCode, null);
    }

    private ResponseEntity<Map<String, Object>> otpSentResponse(String otpCode, Boolean userExists) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "OTP sent successfully");
        body.put("expiresIn", 300);
        if (userExists != null) {
            body.put("userExists", userExists);
        }
        if (isDevProfile()) {
            body.put("otp", otpCode);
        }
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> otpEmailDeliveryFailed() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "success", false,
                        "error", "Failed to send OTP",
                        "message",
                        "We couldn't deliver the OTP to your email. Please check the address or try again shortly."));
    }

    /**
     * HTML email for consumer OTP flows — table layout for broader client support, branded header + OTP pill.
     *
     * @param headline main title inside the white card (not the masthead)
     * @param introHtml paragraph after “Hello,” — may contain {@code <br>}
     */
    private static String buildBrandedOtpEmail(String otpCode, String headline, String introHtml) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>My Property Fact</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f1f5f9;-webkit-font-smoothing:antialiased;">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" \
                style="background-color:#f1f5f9;">
                  <tr>
                    <td align="center" style="padding:32px 16px;">
                      <table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0" \
                      style="max-width:600px;width:100%%;background:#ffffff;border-radius:16px;\
                      overflow:hidden;box-shadow:0 12px 40px rgba(15,23,42,0.08);border:1px solid #e2e8f0;">
                        <tr>
                          <td style="background:linear-gradient(135deg,#1e3a8a 0%%,#2563eb 50%%,#3b82f6 100%%);\
                          padding:28px 28px;text-align:center;">
                            <div style="font-family:Georgia,'Times New Roman',serif;font-size:22px;\
                            font-weight:700;color:#ffffff;letter-spacing:0.02em;line-height:1.2;\
                            margin:0;">My Property Fact</div>
                            <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,\
                            Helvetica,Arial,sans-serif;font-size:13px;color:rgba(255,255,255,0.88);\
                            margin-top:8px;line-height:1.4;">Smart real estate decisions</div>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:36px 32px 28px;\
                          font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,\
                          Helvetica,Arial,sans-serif;">
                            <h1 style="margin:0 0 14px;font-size:21px;line-height:1.35;\
                            font-weight:700;color:#0f172a;">%s</h1>
                            <p style="margin:0 0 22px;font-size:15px;line-height:1.65;color:#475569;">
                              Hello,<br><br>%s
                            </p>
                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0">
                              <tr>
                                <td align="center" style="padding:22px 18px;\
                                background:linear-gradient(180deg,#f8fafc 0%%,#f1f5f9 100%%);\
                                border-radius:14px;border:1px solid #e2e8f0;">
                                  <div style="font-size:11px;font-weight:700;color:#64748b;\
                                  text-transform:uppercase;letter-spacing:0.14em;margin-bottom:12px;">\
                                  Verification code</div>
                                  <div style="font-family:'SF Mono','Courier New',Consolas,monospace;\
                                  font-size:34px;font-weight:700;color:#0f172a;letter-spacing:10px;\
                                  line-height:1.2;">%s</div>
                                </td>
                              </tr>
                            </table>
                            <p style="margin:22px 0 0;font-size:13px;line-height:1.65;color:#64748b;">
                              This code expires in <strong style="color:#334155;">5 minutes</strong>. \
                              Never share it — My Property Fact staff will never ask for your OTP.
                            </p>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:22px 32px 28px;background:#f8fafc;\
                          border-top:1px solid #e2e8f0;\
                          font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,\
                          Helvetica,Arial,sans-serif;font-size:12px;color:#94a3b8;text-align:center;\
                          line-height:1.55;">
                            <div style="margin-bottom:10px;color:#64748b;">
                              <a href="https://mypropertyfact.in" style="color:#2563eb;\
                              text-decoration:none;font-weight:600;">mypropertyfact.in</a>
                              &nbsp;·&nbsp;
                              Questions? Reply to this email or contact support via our website.
                            </div>
                            <div style="font-size:11px;color:#cbd5e1;">© My Property Fact · \
                            This is an automated security message.</div>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(headline, introHtml, otpCode);
    }


    public ResponseEntity<?> session(Authentication authentication, HttpServletRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String expiresAt = jwtService.getExpiryFromCookie(request);
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("email", user.getEmail());
        body.put("roles", roles);
        body.put("expiresAt", expiresAt);
        body.put("userId", user.getId());
        body.put("fullName", user.getFullName());
        body.put("dashboardUsername", user.getDashboardUsername());
        body.put("permissions",
                new ArrayList<>(adminPermissionService.effectivePermissions(user)));

        return ResponseEntity.ok(body);
    }

    /**
     * Labels and keys for Super Admin when editing Admin users (Manage Users).
     */
    public ResponseEntity<?> adminPermissionDefinitions() {
        return ResponseEntity.ok(AdminPermissionKeys.definitions());
    }

    /**
     * Whether the current admin may use enquiries APIs and if they must enter the 4-digit PIN first.
     */
    public ResponseEntity<?> enquiryAccessStatus(
            Authentication authentication, HttpServletRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userRoleService.userHasRole(user.getId(), "SUPERADMIN")) {
            return ResponseEntity.ok(Map.of(
                    "hasPermission", true,
                    "needsCode", false,
                    "unlocked", true));
        }
        boolean hasPerm = adminPermissionService.can(user, AdminPermissionKeys.MANAGE_ENQUIRIES);
        if (!hasPerm) {
            return ResponseEntity.ok(Map.of(
                    "hasPermission", false,
                    "needsCode", false,
                    "unlocked", false));
        }
        User db = userRepository.findById(user.getId()).orElse(user);
        boolean pinConfigured = db.getEnquiryAccessPinHash() != null && !db.getEnquiryAccessPinHash().isBlank();
        boolean unlocked = enquiryAccessService.canAccessEnquiries(db, request);
        return ResponseEntity.ok(Map.of(
                "hasPermission", true,
                "needsCode", pinConfigured,
                "pinConfigured", pinConfigured,
                "unlocked", unlocked));
    }

    /**
     * Validates the 4-digit PIN and sets the HttpOnly {@code enquiryUnlock} cookie (ADMIN with permission only).
     */
    public ResponseEntity<?> unlockEnquiries(
            Map<String, String> body,
            HttpServletResponse response,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (userRoleService.userHasRole(user.getId(), "SUPERADMIN")) {
            return ResponseEntity.ok(Map.of("unlocked", true));
        }
        if (!adminPermissionService.can(user, AdminPermissionKeys.MANAGE_ENQUIRIES)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have enquiries access."));
        }
        User db = userRepository.findById(user.getId()).orElseThrow();
        if (db.getEnquiryAccessPinHash() == null || db.getEnquiryAccessPinHash().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "No access code is configured. Contact a Super Admin."));
        }
        String code = body != null ? body.get("code") : null;
        if (code == null || !code.trim().matches("\\d{4}")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Enter a valid 4-digit code."));
        }
        if (!passwordEncoder.matches(code.trim(), db.getEnquiryAccessPinHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Incorrect code."));
        }
        long maxAgeSeconds = jwtService.getEnquiryUnlockExpirationMs() / 1000L;
        String token = jwtService.generateEnquiryUnlockToken(db);
        response.addHeader("Set-Cookie", buildAuthCookie("enquiryUnlock", token, maxAgeSeconds).toString());
        return ResponseEntity.ok(Map.of("unlocked", true));
    }

    // Handling logout
    public ResponseEntity<?> logout(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildAuthCookie("token", "", 0).toString());
        response.addHeader("Set-Cookie", buildAuthCookie("refreshToken", "", 0).toString());
        response.addHeader("Set-Cookie", buildAuthCookie("enquiryUnlock", "", 0).toString());
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    public ResponseEntity<?> refreshAccessToken(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = null;
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("refreshToken".equals(c.getName())) {
                    refreshToken = c.getValue();
                }
            }
        }
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            String newAccessToken = jwtService.generateTokenFromRefresh(refreshToken);
            response.addHeader("Set-Cookie",
                    buildAuthCookie("token", newAccessToken, accessTokenExpiration / 1000).toString());
            return ResponseEntity.ok(Map.of("message", "Token refreshed"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "message",
                            "Invalid refresh session or account not activated."));
        }
    }

    /**
     * Omit cookie {@code Domain} when blank so browsers use host-only cookies (needed for local dev
     * on localhost with different ports for UI and API).
     */
    private ResponseCookie buildAuthCookie(String name, String value, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(httpSecure)
                .path("/")
                .sameSite(httpSecure ? "None" : "Lax")
                .maxAge(maxAgeSeconds);
        if (cookiesDomain != null && !cookiesDomain.isBlank()) {
            b = b.domain(cookiesDomain.trim());
        }
        return b.build();
    }
}
