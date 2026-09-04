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
}
