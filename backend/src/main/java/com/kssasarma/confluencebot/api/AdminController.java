package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.AdminUserEventResponse;
import com.kssasarma.confluencebot.api.dto.AdminUserRequest;
import com.kssasarma.confluencebot.api.dto.AdminUserResponse;
import com.kssasarma.confluencebot.email.EmailService;
import com.kssasarma.confluencebot.user.AdminUserEvent;
import com.kssasarma.confluencebot.user.AdminUserEventRepository;
import com.kssasarma.confluencebot.user.RefreshTokenRepository;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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
    private final EmailService emailService;
    private final AdminUserEventRepository eventRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                            EmailService emailService, AdminUserEventRepository eventRepository,
                            RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.eventRepository = eventRepository;
        this.refreshTokenRepository = refreshTokenRepository;
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
        boolean emailSent = emailService.sendWelcomeEmail(request.email(), auth.getName(), tempPassword);
        eventRepository.save(AdminUserEvent.of(AdminUserEvent.EventType.CREATED, auth.getName(), saved, emailSent));
        logger.info("Admin {} created new user {} with roles {} (welcome email {})",
                auth.getName(), request.email(), roles, emailSent ? "sent" : "not sent");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("user", AdminUserResponse.from(saved), "tempPassword", tempPassword, "emailSent", emailSent));
    }

    @Operation(summary = "Re-send the welcome email with a fresh temporary password")
    @PostMapping("/users/{id}/resend-welcome")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> resendWelcome(@PathVariable Long id, Authentication auth) {
        return userRepository.findById(id)
                .map(u -> {
                    String tempPassword = generateTempPassword();
                    u.setPassword(passwordEncoder.encode(tempPassword));
                    u.setMustChangePassword(true);
                    User saved = userRepository.save(u);
                    // The old password (and anything issued under it) must stop working the moment
                    // a new one is generated — the same invariant AuthServiceImpl.changePassword
                    // enforces for a user changing their own password.
                    refreshTokenRepository.revokeAllByUserId(saved.getId());

                    boolean emailSent = emailService.sendWelcomeEmail(saved.getEmail(), auth.getName(), tempPassword);
                    eventRepository.save(AdminUserEvent.of(AdminUserEvent.EventType.RESENT, auth.getName(), saved, emailSent));
                    logger.info("Admin {} resent welcome email to user {} (id={}, email {})",
                            auth.getName(), saved.getEmail(), id, emailSent ? "sent" : "not sent");

                    return ResponseEntity.ok((Object) Map.of(
                            "user", AdminUserResponse.from(saved), "tempPassword", tempPassword, "emailSent", emailSent));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a user account and everything scoped to it (chats, sessions, preferences)")
    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> deleteUser(@PathVariable Long id, Authentication auth) {
        return userRepository.findById(id)
                .map(u -> {
                    if (u.getEmail().equals(auth.getName())) {
                        return ResponseEntity.badRequest().body((Object) ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_REQUEST, "You cannot delete your own account"));
                    }
                    eventRepository.save(AdminUserEvent.deleted(auth.getName(), u));
                    userRepository.delete(u);
                    logger.info("Admin {} deleted user {} (id={})", auth.getName(), u.getEmail(), id);
                    return ResponseEntity.noContent().<Object>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "List admin actions on user accounts (create, resend, delete), most recent first")
    @GetMapping("/audit")
    public ResponseEntity<List<AdminUserEventResponse>> listAuditEvents(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        Page<AdminUserEvent> events = eventRepository.findAllByOrderByCreatedAtDesc(pageable);
        return ResponseEntity.ok(events.map(AdminUserEventResponse::from).getContent());
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
            if (value == null) throw new IllegalArgumentException("Role must not be null");
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
