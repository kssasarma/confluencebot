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
        return new UserInfoResponse(user.getId(), user.getEmail(), user.getName(),
                UserRole.namesOf(user.getRoles()), user.isMustChangePassword());
    }

    @Operation(summary = "Change the password and re-issue tokens")
    @PostMapping("/change-password")
    public AuthResponse changePassword(@AuthenticationPrincipal User user,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(user, request);
    }

    @Operation(summary = "Set the signed-in user's own display name (email cannot be changed)")
    @PatchMapping("/name")
    public UserInfoResponse updateName(@AuthenticationPrincipal User user,
                                       @Valid @RequestBody UpdateNameRequest request) {
        return authService.updateName(user, request);
    }

    @Operation(summary = "Request a one-time password-reset code by email",
            description = """
                    Always responds the same way whether or not the address is registered, to \
                    avoid confirming which emails have accounts. `emailSent: false` means mail is \
                    down or misconfigured, not that the address is unknown — the reader's recourse \
                    then is asking an admin to re-share a temporary password from Settings, which \
                    works independently of the mail relay.
                    """)
    @PostMapping("/forgot-password/request")
    public ForgotPasswordResponse requestPasswordReset(@Valid @RequestBody ForgotPasswordRequest request) {
        return new ForgotPasswordResponse(authService.requestPasswordReset(request));
    }

    @Operation(summary = "Redeem a one-time code for a new password",
            description = "Signs the user in with the new password, the same as changing it "
                    + "while already signed in.")
    @PostMapping("/forgot-password/reset")
    public AuthResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }
}
