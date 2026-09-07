package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.user.User;

/** Issues, rotates and revokes the credentials a signed-in client carries. */
public interface AuthService {

    AuthResponse login(LoginRequest request);

    /** Rotates a refresh token: the presented token is revoked and a fresh pair is issued. */
    AuthResponse refresh(RefreshRequest request);

    void logout(RefreshRequest request);

    AuthResponse changePassword(User user, ChangePasswordRequest request);

    UserInfoResponse updateName(User user, UpdateNameRequest request);

    /**
     * Emails a one-time reset code, if the address belongs to an account. Always returns whether
     * an email was actually sent (not whether the account exists) — an admin re-sharing a
     * temporary password is the fallback when this reports {@code false}, e.g. because mail is
     * down, so the caller needs to know to point the user there.
     */
    boolean requestPasswordReset(ForgotPasswordRequest request);

    /**
     * Redeems a one-time code for a new password and signs the user in with it, the same as
     * {@link #changePassword} does for a self-service change.
     *
     * @throws org.springframework.security.authentication.BadCredentialsException when the email,
     *         code or attempt count don't check out
     */
    AuthResponse resetPassword(ResetPasswordRequest request);
}
