package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.AdminUserRequest;
import com.kssasarma.confluencebot.api.dto.AdminUserResponse;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Tag(name = "Admin", description = "User management — admin only")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

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
    public ResponseEntity<?> createUser(@Valid @RequestBody AdminUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email already in use"));
        }

        UserRole role;
        try {
            role = request.role() != null ? UserRole.valueOf(request.role().toUpperCase()) : UserRole.USER;
        } catch (IllegalArgumentException e) {
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("user", AdminUserResponse.from(saved), "tempPassword", tempPassword));
    }

    @Operation(summary = "Enable or disable a user account")
    @PatchMapping("/users/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return userRepository.findById(id)
                .map(u -> {
                    u.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
                    return ResponseEntity.ok((Object) AdminUserResponse.from(userRepository.save(u)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String generateTempPassword() {
        byte[] bytes = new byte[9];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
