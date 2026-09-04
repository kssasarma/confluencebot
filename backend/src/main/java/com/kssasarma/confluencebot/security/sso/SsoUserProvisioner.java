package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoProperties;
import com.kssasarma.confluencebot.user.AuthProvider;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a verified directory identity into the local account the rest of the application works
 * with.
 *
 * <p>Everything downstream — conversations, preferences, the admin screen — is keyed on a row in
 * {@code users}, so somebody arriving from a directory for the first time needs one created. That
 * is all this does. Which role they get is a fixed default, not a reading of their directory
 * groups: mapping groups to roles is authorization, and this build does not do authorization.
 *
 * <p>Identity is keyed on the provider's subject, not the address, and scoped to the provider that
 * issued it. An address can be renamed, and reassigned to somebody else entirely; a subject is the
 * directory's own answer to "which person is this", and is the only field here that can safely be
 * treated as an identifier — within its own directory, which is why the provider is stored beside
 * it.
 */
@Service
public class SsoUserProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SsoUserProvisioner.class);

    private final UserRepository userRepository;
    private final SsoProperties properties;

    public SsoUserProvisioner(UserRepository userRepository, SsoProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    /**
     * Finds, links or creates the account behind an authenticated principal.
     *
     * @param providerId the registration the sign-in came through — carried rather than read from
     *                   configuration so that adding a second provider changes what is passed in
     *                   here and nothing about what this method does with it
     * @throws SsoProvisioningException when the principal carries no usable address, the account
     *                                  is disabled, or the address belongs to somebody else
     */
    @Transactional
    public User provision(String providerId, OAuth2User principal) {
        String subject = subjectOf(principal);
        String email = emailOf(principal);

        User user = userRepository.findBySsoProviderIdAndExternalId(providerId, subject)
                .map(existing -> reconcile(existing, email))
                .orElseGet(() -> linkOrCreate(providerId, subject, email));

        if (!user.isEnabled()) {
            // The directory let them in; this application has not. Said plainly rather than as a
            // generic sign-in failure, because "my password works everywhere else" is the first
            // thing anyone hitting this would go and check.
            throw new SsoProvisioningException("This account has been disabled. Contact an administrator.");
        }

        // A temporary password nobody will ever type is not a reason to hold someone at the
        // change-password wall: they just proved who they are against the directory, and the
        // screen behind that wall would ask them for a password they were never given.
        if (user.isMustChangePassword()) {
            user.setMustChangePassword(false);
        }

        return user;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Known subject: the account is already linked, so only the address can have moved. */
    private User reconcile(User user, String email) {
        if (email.equalsIgnoreCase(user.getEmail())) {
            return user;
        }
        Optional<User> holder = userRepository.findByEmailIgnoreCase(email);
        if (holder.isPresent() && !isSameAccount(holder.get(), user)) {
            // Renaming into an address another account already owns would break the unique index,
            // and picking a winner is a judgement no rule here can make correctly. Sign them in
            // under the account the directory says is theirs and leave the collision visible.
            log.warn("Provider '{}' reports address {} for subject {}, but that address belongs to "
                            + "another account. Keeping {} — resolve the duplicate from the admin screen.",
                    user.getSsoProviderId(), email, user.getExternalId(), user.getEmail());
            return user;
        }
        log.info("Address for subject {} at provider '{}' changed from {} to {}",
                user.getExternalId(), user.getSsoProviderId(), user.getEmail(), email);
        user.setEmail(email);
        return user;
    }

    /** New subject: either an existing local account to adopt, or nobody yet. */
    private User linkOrCreate(String providerId, String subject, String email) {
        Optional<User> byEmail = userRepository.findByEmailIgnoreCase(email);
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            if (existing.isSsoLinked()) {
                throw new SsoProvisioningException(
                        "This email address is already linked to a different directory account.");
            }
            // An account that already exists here keeps its role and its password. That matters
            // most for the bootstrap administrator: a directory outage must not be the same thing
            // as losing the only way into the admin screen.
            existing.setSsoProviderId(providerId);
            existing.setExternalId(subject);
            log.info("Linked existing account {} to subject {} at provider '{}'",
                    existing.getEmail(), subject, providerId);
            return existing;
        }

        User created = new User();
        created.setEmail(email);
        created.setPassword(null);
        created.setRole(properties.defaultRole());
        created.setAuthProvider(AuthProvider.SSO);
        created.setSsoProviderId(providerId);
        created.setExternalId(subject);
        created.setEnabled(true);
        created.setMustChangePassword(false);

        User saved = userRepository.save(created);
        log.info("Provisioned {} from provider '{}' with role {}",
                saved.getEmail(), providerId, saved.getRole());
        return saved;
    }

    /**
     * Whether two loaded rows are the same account.
     *
     * <p>Identity first, because within one persistence context Hibernate hands back the same
     * instance for the same row; the identifier is the fallback for the rows this method is
     * handed outside one. Comparing identifiers alone would read two unsaved accounts, both with
     * a null id, as the same one.
     */
    private static boolean isSameAccount(User one, User other) {
        return one == other || (one.getId() != null && Objects.equals(one.getId(), other.getId()));
    }

    private String subjectOf(OAuth2User principal) {
        // getName() is the claim named by app.sso.user-name-attribute; `sub` is the fallback for a
        // provider configuration that pointed it somewhere that turned out to be absent.
        String subject = principal.getName();
        if (isBlank(subject)) {
            subject = asText(principal.getAttribute("sub"));
        }
        if (isBlank(subject)) {
            throw new SsoProvisioningException(
                    "The identity provider returned no '" + properties.userNameAttribute()
                            + "' claim, so this sign-in cannot be tied to an account.");
        }
        return subject;
    }

    private String emailOf(OAuth2User principal) {
        for (String claim : properties.emailClaims()) {
            String value = asText(principal.getAttribute(claim));
            if (!isBlank(value)) {
                return value.trim().toLowerCase(Locale.ROOT);
            }
        }
        throw new SsoProvisioningException(
                "The identity provider returned no email address (looked for "
                        + String.join(", ", properties.emailClaims())
                        + "). Release one of those claims to this client, or point "
                        + "app.sso.email-claims at the one it does release.");
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
