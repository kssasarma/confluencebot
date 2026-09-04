package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Authentication", description = "Sign-in, token rotation and password management")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Sign in and receive an access/refresh token pair")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Exchange a refresh token for a fresh token pair")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "Revoke a refresh token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Describe the signed-in user")
    @GetMapping("/me")
    public UserInfoResponse me(@AuthenticationPrincipal User user) {
        return new UserInfoResponse(user.getId(), user.getEmail(),
                UserRole.namesOf(user.getRoles()), user.isMustChangePassword());
    }

    @Operation(summary = "Change the password and re-issue tokens")
    @PostMapping("/change-password")
    public AuthResponse changePassword(@AuthenticationPrincipal User user,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(user, request);
    }
}
