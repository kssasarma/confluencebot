package com.kssasarma.confluencebot.auth;

import com.kssasarma.confluencebot.config.SsoProperties;
import com.kssasarma.confluencebot.exception.InvalidSsoCodeException;
import com.kssasarma.confluencebot.user.SsoLoginCode;
import com.kssasarma.confluencebot.user.SsoLoginCodeRepository;
import com.kssasarma.confluencebot.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class SsoServiceImpl implements SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoServiceImpl.class);

    /** 256 bits, so guessing one inside its one-minute window is not a strategy. */
    private static final int CODE_BYTES = 32;

    private final SsoProperties properties;
    private final SsoLoginCodeRepository loginCodeRepository;
    private final TokenIssuer tokenIssuer;
    private final SecureRandom secureRandom = new SecureRandom();

    public SsoServiceImpl(SsoProperties properties,
                          SsoLoginCodeRepository loginCodeRepository,
                          TokenIssuer tokenIssuer) {
        this.properties = properties;
        this.loginCodeRepository = loginCodeRepository;
        this.tokenIssuer = tokenIssuer;
    }

    // Reads configuration and touches no table, so it opens no transaction — it is the one call
    // here made by a browser that has not authenticated, on every load of the sign-in screen.
    @Override
    public SsoStatusResponse describe() {
        if (!properties.enabled()) {
            return SsoStatusResponse.disabled();
        }
        return new SsoStatusResponse(
                true,
                properties.providerId(),
                properties.providerName(),
                properties.authorizationRequestUri(),
                StringUtils.hasText(properties.logoutUri()) ? properties.logoutUri() : null);
    }

    @Override
    @Transactional
    public String issueLoginCode(User user) {
        // These rows are only ever read within a minute of being written, so the cheapest place to
        // keep the table bounded is here rather than on a scheduler this application does not run.
        // Anything already past its expiry is unredeemable by the conditional update below, so
        // deleting it cannot take a live code with it.
        loginCodeRepository.deleteExpiredBefore(Instant.now());

        byte[] raw = new byte[CODE_BYTES];
        secureRandom.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        SsoLoginCode entity = new SsoLoginCode();
        entity.setCodeHash(sha256Hex(code));
        entity.setUser(user);
        entity.setExpiresAt(Instant.now().plus(properties.codeTtl()));
        loginCodeRepository.save(entity);

        log.debug("Issued a single sign-on hand-off code for user {}", user.getEmail());
        return code;
    }

    @Override
    @Transactional
    public AuthResponse exchangeLoginCode(String code) {
        String hash = sha256Hex(code);

        // The update is the authorization: whoever it matches is the one caller allowed to
        // continue, and a replay a millisecond later matches nothing.
        if (loginCodeRepository.consume(hash, Instant.now()) != 1) {
            throw new InvalidSsoCodeException("This sign-in link is no longer valid. Please sign in again.");
        }

        SsoLoginCode consumed = loginCodeRepository.findByCodeHashWithUser(hash)
                .orElseThrow(() -> new InvalidSsoCodeException("This sign-in link is no longer valid. Please sign in again."));

        User user = consumed.getUser();
        if (!user.isEnabled()) {
            throw new InvalidSsoCodeException("This account is disabled.");
        }

        log.info("Single sign-on completed for {}", user.getEmail());
        return tokenIssuer.issue(user);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM this can run on", e);
        }
    }
}
