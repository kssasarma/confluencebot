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
import java.util.List;
import java.util.Map;

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

        UserRole role;
        try {
            role = request.role() != null ? UserRole.valueOf(request.role().toUpperCase()) : UserRole.USER;
        } catch (IllegalArgumentException e) {
            logger.warn("Admin {} attempted to create user with invalid role: {}", auth.getName(), request.role());
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid role: " + request.role()));
        }

        String tempPassword = (request.tempPassword() != null && !request.tempPassword().isBlank())
                ? request.tempPassword()
                : generateTempPassword();

        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setRole(role);
        user.setMustChangePassword(true);

        User saved = userRepository.save(user);
        logger.info("Admin {} created new user {} with role {}", auth.getName(), request.email(), role);
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

    @Operation(summary = "Modify a user's role (promote or demote)")
    @PatchMapping("/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateRole(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        String newRoleStr = body.get("role");
        if (newRoleStr == null || newRoleStr.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Role field is required"));
        }

        UserRole newRole;
        try {
            newRole = UserRole.valueOf(newRoleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Admin {} attempted to set invalid role: {}", auth.getName(), newRoleStr);
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid role: " + newRoleStr));
        }

        return userRepository.findById(id)
                .map(u -> {
                    UserRole oldRole = u.getRole();

                    // Self-demotion is the one change nobody can undo for you: the request that
                    // strips your own ADMIN is also the last one you are authorised to make here.
                    if (newRole != UserRole.ADMIN && u.getEmail().equals(auth.getName())) {
                        logger.warn("Admin {} attempted to demote themselves to {}", auth.getName(), newRole);
                        return ResponseEntity.badRequest().body((Object) ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_REQUEST, "You cannot demote your own account; ask another admin"));
                    }

                    u.setRole(newRole);
                    User saved = userRepository.save(u);
                    logger.info("Admin {} changed role for user {} (id={}) from {} to {}", auth.getName(), u.getEmail(), id, oldRole, newRole);
                    return ResponseEntity.ok((Object) AdminUserResponse.from(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String generateTempPassword() {
        byte[] bytes = new byte[9];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
