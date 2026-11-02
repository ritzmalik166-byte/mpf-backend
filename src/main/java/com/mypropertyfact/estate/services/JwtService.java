package com.mypropertyfact.estate.services;

import com.mypropertyfact.estate.entities.MasterRole;
import com.mypropertyfact.estate.entities.User;
import com.mypropertyfact.estate.repositories.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {
    @Value("${security.jwt.secret-key}")
    private String secretKey;

    @Value("${security.jwt.expiration-time}")
    private long jwtExpiration;

    @Value("${security.jwt.refresh.expiration-time}")
    private long jwtRefreshTokenExpiration;

    @Value("${security.jwt.enquiry-unlock-expiration-ms:28800000}")
    private long enquiryUnlockExpirationMs;

    /** Forgot-password link validity (milliseconds). Default 15 minutes. */
    @Value("${app.auth.password-reset-token-ms:900000}")
    private long passwordResetTokenMs;

    private final UserRepository userRepository;
    private final UserRoleService userRoleService;

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(User user) {
        return generateToken(new HashMap<>(), user, jwtExpiration);
    }

    public String generateRefreshToken(User user) {
        return generateToken(new HashMap<>(), user, jwtRefreshTokenExpiration);
    }

    private static void requirePortalApproved(User user) {
        if (user.needsPortalActivation()) {
            throw new IllegalStateException(
                    "This account has not yet been activated by a Super Administrator.");
        }
    }

    public String generateToken(Map<String, Object> extraClaims, User user, long expiration) {
        requirePortalApproved(user);
        Set<String> userRoles = user.getRoles() != null
                ? user.getRoles().stream()
                        .filter(role -> role != null && role.getIsActive() != null && role.getIsActive())
                        .filter(role -> !isUnapprovedAdminStaff(user, role))
                        .map(role -> "ROLE_" + role.getRoleName())
                        .collect(Collectors.toSet())
                : Set.of("ROLE_USER");
        extraClaims.put("role", userRoles);
        extraClaims.put("email", user.getEmail());
        extraClaims.put("userId", user.getId());
        extraClaims.put("fullName", user.getFullName());
        extraClaims.put("tv", user.getTokenVersion() != null ? user.getTokenVersion() : 0);
        return buildToken(extraClaims, user, expiration);
    }

    private static boolean isUnapprovedAdminStaff(User user, MasterRole role) {
        return role.getRoleName() != null
                && "ADMIN".equalsIgnoreCase(role.getRoleName())
                && Boolean.FALSE.equals(user.getAdminStaffApproved());
    }

    public long getExpirationTime() {
        return jwtExpiration;
    }

    private String buildToken(
            Map<String, Object> extraClaims,
            User user,
            long expiration) {
        return Jwts
                .builder()
                .setClaims(extraClaims)
                .setSubject(user.getUsername())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts
                .parserBuilder()
                .setSigningKey(getSignInKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public Claims validateToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSignInKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract roles from JWT token
     */
    @SuppressWarnings("unchecked")
    public Set<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        Object roleClaim = claims.get("role");
        if (roleClaim instanceof Set) {
            return (Set<String>) roleClaim;
        } else if (roleClaim instanceof List) {
            return new HashSet<>((List<String>) roleClaim);
        } else if (roleClaim instanceof String) {
            return Set.of((String) roleClaim);
        }
        return Set.of("ROLE_USER"); // Default role
    }

    /**
     * Extract permissions from JWT token
     */
    @SuppressWarnings("unchecked")
    public List<String> extractPermissions(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("permissions", List.class);
    }

    /**
     * Extract user ID from JWT token
     */
    public Integer extractUserId(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("userId", Integer.class);
    }

    /**
     * Extract token version from JWT (used for single-session invalidation for admin).
     * Returns null if claim is missing (legacy tokens).
     */
    public Integer extractTokenVersion(String token) {
        try {
            Claims claims = extractAllClaims(token);
            Object tv = claims.get("tv");
            if (tv instanceof Number) {
                return ((Number) tv).intValue();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract full name from JWT token
     */
    public String extractFullName(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("fullName", String.class);
    }

    public String getExpiryFromCookie(HttpServletRequest request) {
        String token = null;
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("token".equals(c.getName())) {
                    log.info("Token {}", c.getValue());
                    token = c.getValue();
                    break;
                }
            }
        }
        if (token == null) {
            return null;
        }
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().toInstant().toString(); // ISO format
        } catch (Exception e) {
            return null; // expired or invalid token
        }
    }

    public String generateTokenFromRefresh(String refreshToken) {
        if (!isRefreshTokenValid(refreshToken)) {
            throw new RuntimeException("Invalid refresh token");
        }
        String identity = extractUsername(refreshToken);
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(identity)
                .or(() -> userRepository.findByPhone(identity));
        if (userOpt.isEmpty()) {
            throw new RuntimeException("User not found");
        }
        User user = userOpt.get();
        if (user.needsPortalActivation()) {
            throw new RuntimeException(
                    "This account has not yet been activated by a Super Administrator.");
        }
        if (userRoleService.userHasRole(user.getId(), "SUPERADMIN")
                || userRoleService.userHasRole(user.getId(), "ADMIN")) {
            Integer tokenVersion = extractTokenVersion(refreshToken);
            Integer currentVersion = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
            if (!Objects.equals(tokenVersion, currentVersion)) {
                throw new RuntimeException("Session invalidated by another login");
            }
        }
        return generateToken(user);
    }

    public boolean isRefreshTokenValid(String refreshToken) {
        try {
            extractAllClaims(refreshToken);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public long getEnquiryUnlockExpirationMs() {
        return enquiryUnlockExpirationMs;
    }

    public String generateEnquiryUnlockToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("purpose", "ENQUIRY_UNLOCK");
        return Jwts
                .builder()
                .setClaims(claims)
                .setSubject(user.getEmail())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + enquiryUnlockExpirationMs))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isEnquiryUnlockTokenValid(String token, String userEmail) {
        if (token == null || userEmail == null) {
            return false;
        }
        try {
            Claims claims = extractAllClaims(token);
            if (!"ENQUIRY_UNLOCK".equals(claims.get("purpose"))) {
                return false;
            }
            return userEmail.equals(claims.getSubject()) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    /** Payload validated from a forgot-password JWT (purpose {@code PASSWORD_RESET}). */
    public record PasswordResetTokenPayload(String email, int tokenVersion) {}

    public String generatePasswordResetToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("purpose", "PASSWORD_RESET");
        claims.put("tv", user.getTokenVersion() != null ? user.getTokenVersion() : 0);
        return Jwts
                .builder()
                .setClaims(claims)
                .setSubject(user.getEmail())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + passwordResetTokenMs))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Parses a password-reset JWT: correct purpose, not expired, subject present.
     */
    public Optional<PasswordResetTokenPayload> parsePasswordResetToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = extractAllClaims(token);
            if (!"PASSWORD_RESET".equals(claims.get("purpose"))) {
                return Optional.empty();
            }
            if (isTokenExpired(token)) {
                return Optional.empty();
            }
            String email = claims.getSubject();
            if (email == null || email.isBlank()) {
                return Optional.empty();
            }
            Object tv = claims.get("tv");
            int v = tv instanceof Number ? ((Number) tv).intValue() : 0;
            return Optional.of(new PasswordResetTokenPayload(email.trim(), v));
        } catch (Exception e) {
            log.debug("Invalid password reset token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Builds a front-end reset URL; {@code baseUrl} should be the site origin (no trailing slash).
     */
    public static String buildPasswordResetLink(String baseUrl, String resetJwt) {
        String base = baseUrl != null ? baseUrl.trim() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String enc = URLEncoder.encode(resetJwt, StandardCharsets.UTF_8);
        return base + "/reset-password?token=" + enc;
    }
}
