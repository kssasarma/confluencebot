package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.AdminUserRequest;
import com.kssasarma.confluencebot.api.dto.AdminUserResponse;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Tag(name = "Admin", description = "User management — admin only")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'ADMIN_READ_ONLY')")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Operation(summary = "List all users")
    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> listUsers() {
        return ResponseEntity.ok(
                userRepository.findAll().stream().map(AdminUserResponse::from).toList());
    }

    @Operation(summary = "Create a new user with a temporary password")
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@Valid @RequestBody AdminUserRequest request, Authentication auth) {
        if (userRepository.existsByEmail(request.email())) {
            logger.warn("Admin {} attempted to create user with email already in use: {}", auth.getName(), request.email());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email already in use"));
        }

        Set<UserRole> roles;
        try {
            roles = parseRoles(request.roles());
        } catch (IllegalArgumentException e) {
            logger.warn("Admin {} attempted to create user with invalid roles: {}", auth.getName(), request.roles());
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid role in: " + request.roles()));
        }

        String tempPassword = (request.tempPassword() != null && !request.tempPassword().isBlank())
                ? request.tempPassword()
                : generateTempPassword();

        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setRoles(roles);
        user.setMustChangePassword(true);

        User saved = userRepository.save(user);
        logger.info("Admin {} created new user {} with roles {}", auth.getName(), request.email(), roles);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("user", AdminUserResponse.from(saved), "tempPassword", tempPassword));
    }

    @Operation(summary = "Enable or disable a user account")
    @PatchMapping("/users/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body, Authentication auth) {
        return userRepository.findById(id)
                .map(u -> {
                    boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
                    if (!enabled && u.getEmail().equals(auth.getName())) {
                        return ResponseEntity.badRequest().body((Object) ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_REQUEST, "You cannot disable your own account"));
                    }
                    u.setEnabled(enabled);
                    User saved = userRepository.save(u);
                    logger.info("Admin {} {} user {} (id={})", auth.getName(), enabled ? "enabled" : "disabled", u.getEmail(), id);
                    return ResponseEntity.ok((Object) AdminUserResponse.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Replace a user's roles (promote, demote, or grant an additional role)")
    @PatchMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateRoles(@PathVariable Long id, @RequestBody Map<String, List<String>> body, Authentication auth) {
        List<String> requested = body.get("roles");
        if (requested == null || requested.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "roles must contain at least one role"));
        }

        Set<UserRole> newRoles;
        try {
            newRoles = parseRoles(requested);
        } catch (IllegalArgumentException e) {
            logger.warn("Admin {} attempted to set invalid roles: {}", auth.getName(), requested);
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid role in: " + requested));
        }

        return userRepository.findById(id)
                .map(u -> {
                    Set<UserRole> oldRoles = u.getRoles();

                    // Self-demotion is the one change nobody can undo for you: the request that
                    // strips your own ADMIN is also the last one you are authorised to make here.
                    if (!newRoles.contains(UserRole.ADMIN) && u.getEmail().equals(auth.getName())) {
                        logger.warn("Admin {} attempted to remove their own ADMIN role", auth.getName());
                        return ResponseEntity.badRequest().body((Object) ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_REQUEST, "You cannot remove your own admin role; ask another admin"));
                    }

                    u.setRoles(newRoles);
                    User saved = userRepository.save(u);
                    logger.info("Admin {} changed roles for user {} (id={}) from {} to {}", auth.getName(), u.getEmail(), id, oldRoles, newRoles);
                    return ResponseEntity.ok((Object) AdminUserResponse.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Defaults to {@code {USER}} when the caller sends no roles at all. */
    private Set<UserRole> parseRoles(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of(UserRole.USER);
        Set<UserRole> parsed = new LinkedHashSet<>();
        for (String value : raw) {
            parsed.add(UserRole.valueOf(value.toUpperCase()));
        }
        return parsed;
    }

    private String generateTempPassword() {
        byte[] bytes = new byte[9];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
