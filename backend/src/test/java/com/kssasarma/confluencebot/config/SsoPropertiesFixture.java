package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.user.UserRole;

import java.time.Duration;
import java.util.List;

/**
 * A fully-populated {@link SsoProperties} that tests vary one field of.
 *
 * <p>The record has twenty components and Spring fills nineteen of them from defaults at runtime,
 * so writing the canonical constructor out in every test would bury the one value each test is
 * actually about.
 */
public final class SsoPropertiesFixture {

    private boolean enabled = true;
    private String providerId = "otds";
    private String providerName = "OpenText";
    private String issuerUri = "";
    private String clientId = "confluence-bot";
    private String clientSecret = "s3cret";
    private List<String> scopes = List.of("openid", "profile", "email");
    private String redirectUri = "{baseUrl}/api/login/oauth2/code/{registrationId}";
    private String clientAuthenticationMethod = "client_secret_basic";
    private String authorizationUri = "";
    private String tokenUri = "";
    private String userInfoUri = "";
    private String jwkSetUri = "";
    private String userNameAttribute = "sub";
    private List<String> emailClaims = List.of("email", "mail", "upn", "preferred_username");
    private UserRole defaultRole = UserRole.USER;
    private String loginSuccessUri = "/sso/callback";
    private String logoutUri = "";
    private Duration codeTtl = Duration.ofMinutes(1);

    public static SsoPropertiesFixture aProvider() {
        return new SsoPropertiesFixture();
    }

    /** The shape a deployment gets when it sets nothing but the two endpoints and a client. */
    public static SsoPropertiesFixture withExplicitEndpoints() {
        return new SsoPropertiesFixture()
                .authorizationUri("https://idp.example.com/oauth2/auth")
                .tokenUri("https://idp.example.com/oauth2/token")
                .jwkSetUri("https://idp.example.com/oauth2/jwks");
    }

    public SsoPropertiesFixture enabled(boolean value) { this.enabled = value; return this; }
    public SsoPropertiesFixture providerId(String value) { this.providerId = value; return this; }
    public SsoPropertiesFixture providerName(String value) { this.providerName = value; return this; }
    public SsoPropertiesFixture issuerUri(String value) { this.issuerUri = value; return this; }
    public SsoPropertiesFixture clientId(String value) { this.clientId = value; return this; }
    public SsoPropertiesFixture clientSecret(String value) { this.clientSecret = value; return this; }
    public SsoPropertiesFixture scopes(List<String> value) { this.scopes = value; return this; }
    public SsoPropertiesFixture redirectUri(String value) { this.redirectUri = value; return this; }
    public SsoPropertiesFixture clientAuthenticationMethod(String v) { this.clientAuthenticationMethod = v; return this; }
    public SsoPropertiesFixture authorizationUri(String value) { this.authorizationUri = value; return this; }
    public SsoPropertiesFixture tokenUri(String value) { this.tokenUri = value; return this; }
    public SsoPropertiesFixture userInfoUri(String value) { this.userInfoUri = value; return this; }
    public SsoPropertiesFixture jwkSetUri(String value) { this.jwkSetUri = value; return this; }
    public SsoPropertiesFixture userNameAttribute(String value) { this.userNameAttribute = value; return this; }
    public SsoPropertiesFixture emailClaims(List<String> value) { this.emailClaims = value; return this; }
    public SsoPropertiesFixture defaultRole(UserRole value) { this.defaultRole = value; return this; }
    public SsoPropertiesFixture loginSuccessUri(String value) { this.loginSuccessUri = value; return this; }
    public SsoPropertiesFixture logoutUri(String value) { this.logoutUri = value; return this; }
    public SsoPropertiesFixture codeTtl(Duration value) { this.codeTtl = value; return this; }

    public SsoProperties build() {
        return new SsoProperties(enabled, providerId, providerName, issuerUri, clientId,
                clientSecret, scopes, redirectUri, clientAuthenticationMethod, authorizationUri,
                tokenUri, userInfoUri, jwkSetUri, userNameAttribute, emailClaims, defaultRole,
                loginSuccessUri, logoutUri, codeTtl);
    }
}
