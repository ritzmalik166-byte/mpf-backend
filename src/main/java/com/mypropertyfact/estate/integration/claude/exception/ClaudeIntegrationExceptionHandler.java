package com.mypropertyfact.estate.integration.claude.exception;

import com.mypropertyfact.estate.integration.claude.controller.ClaudeIntegrationController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error mapping for the Claude controller only (ordered before the application's global handler).
 * Responses are machine-readable and never contain stack traces or file-system paths.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ClaudeIntegrationController.class)
public class ClaudeIntegrationExceptionHandler {

    @ExceptionHandler(ClaudeIntegrationException.class)
    public ResponseEntity<Object> handle(ClaudeIntegrationException e) {
        if (e.getBody() != null) {
            return ResponseEntity.status(e.getStatus()).body(e.getBody());
        }
        return ResponseEntity.status(e.getStatus()).body(body(e.getStatus(), e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> accessDenied(AccessDeniedException e) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken;
        HttpStatus status = anonymous ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(body(status, anonymous ? "UNAUTHORIZED" : "FORBIDDEN",
                anonymous ? "Authentication required (Bearer JWT from the admin login)" : "MANAGE_PROJECTS permission is required"));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Object> missing(Exception e) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Upload exceeds the server limit"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Object> multipart(MultipartException e) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART", "Request must be multipart/form-data"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e) {
        log.error("Claude integration error: {}", e.toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error"));
    }

    private static Map<String, Object> body(HttpStatus status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", status.value());
        m.put("error", code);
        m.put("message", message);
        return m;
    }
}
