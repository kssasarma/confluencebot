package com.kssasarma.confluencebot.security.sso;

import com.kssasarma.confluencebot.config.SsoPropertiesFixture;
import com.kssasarma.confluencebot.user.AuthProvider;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a verified OTDS identity turns into on this side.
 *
 * <p>The cases worth pinning down are the ones where an address is not an identifier. A directory
 * renames mailboxes, reassigns them, and hands out the same address to a new person after somebody
 * leaves; an account keyed on the address alone silently follows all three, and following the
 * third means seating a new employee in a departed one's conversations.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoUserProvisionerTest {

    private static final String SUBJECT = "otds-subject-9f3c";

    @Mock private UserRepository userRepository;

    private SsoUserProvisioner provisioner;

    @BeforeEach
    void setUp() {
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByExternalId(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        provisioner = new SsoUserProvisioner(userRepository, SsoPropertiesFixture.aProvider().build());
    }

    @Test
    void anUnknownIdentityIsProvisionedWithNoPasswordAndTheDefaultRole() {
        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "jane@corp.example")));

        assertThat(user.getEmail()).isEqualTo("jane@corp.example");
        assertThat(user.getExternalId()).isEqualTo(SUBJECT);
        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.OTDS);
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        // Nothing to change, nothing to leak, and nothing a stolen password file could be used for.
        assertThat(user.hasNoLocalPassword()).isTrue();
        assertThat(user.isMustChangePassword()).isFalse();
    }

    @Test
    void theDefaultRoleIsWhateverTheDeploymentConfigured() {
        provisioner = new SsoUserProvisioner(userRepository,
                SsoPropertiesFixture.aProvider().defaultRole(UserRole.ADMIN_READ_ONLY).build());

        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "jane@corp.example")));

        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN_READ_ONLY);
    }

    @Test
    void addressesAreStoredFoldedSoOneDirectoryCasingDoesNotBecomeTwoAccounts() {
        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "Jane.Doe@Corp.Example")));

        assertThat(user.getEmail()).isEqualTo("jane.doe@corp.example");
    }

    @Test
    void anExistingLocalAccountIsAdoptedRatherThanDuplicated() {
        User existing = localUser("jane@corp.example", UserRole.ADMIN, "$2b$12$existing-hash");
        when(userRepository.findByEmailIgnoreCase("jane@corp.example")).thenReturn(Optional.of(existing));

        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "jane@corp.example")));

        assertThat(user).isSameAs(existing);
        assertThat(user.getExternalId()).isEqualTo(SUBJECT);
        // Its role and its password both survive. That is what stops a directory outage from also
        // being the loss of the only account that can reach the admin screen.
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(user.hasNoLocalPassword()).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void adoptingAnExistingAccountMatchesRegardlessOfCasing() {
        User existing = localUser("Jane.Doe@corp.example", UserRole.USER, "hash");
        when(userRepository.findByEmailIgnoreCase("jane.doe@corp.example")).thenReturn(Optional.of(existing));

        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "Jane.Doe@Corp.Example")));

        assertThat(user).isSameAs(existing);
    }

    @Test
    void anAddressAlreadyLinkedToAnotherDirectoryIdentityIsRefused() {
        User someoneElse = localUser("jane@corp.example", UserRole.USER, "hash");
        someoneElse.setExternalId("a-different-subject");
        when(userRepository.findByEmailIgnoreCase("jane@corp.example")).thenReturn(Optional.of(someoneElse));

        // The address was reassigned. Seating the new holder in the old one's account would hand
        // them somebody else's conversation history.
        assertThatThrownBy(() -> provisioner.provision(
                principal(Map.of("sub", SUBJECT, "email", "jane@corp.example"))))
                .isInstanceOf(SsoProvisioningException.class)
                .hasMessageContaining("already linked");
    }

    @Test
    void aRenamedMailboxFollowsTheSubjectItBelongsTo() {
        User linked = ssoUser("jane.old@corp.example", SUBJECT);
        when(userRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(linked));

        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "jane.new@corp.example")));

        assertThat(user.getEmail()).isEqualTo("jane.new@corp.example");
    }

    @Test
    void aRenameIntoAnAddressSomebodyElseHoldsIsLeftAloneRatherThanForced() {
        User linked = ssoUser("jane.old@corp.example", SUBJECT);
        User holder = localUser("taken@corp.example", UserRole.USER, "hash");
        // Persisted rows, so they carry identifiers — which is what tells them apart here.
        ReflectionTestUtils.setField(linked, "id", 1L);
        ReflectionTestUtils.setField(holder, "id", 2L);
        when(userRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(linked));
        when(userRepository.findByEmailIgnoreCase("taken@corp.example")).thenReturn(Optional.of(holder));

        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "taken@corp.example")));

        // Signing in still works — it is the right person — but the collision is not resolved by
        // guessing, and the unique index is not walked into.
        assertThat(user).isSameAs(linked);
        assertThat(user.getEmail()).isEqualTo("jane.old@corp.example");
    }

    @Test
    void aDisabledAccountIsStoppedHereEvenThoughTheDirectoryLetThemThrough() {
        User existing = localUser("jane@corp.example", UserRole.USER, "hash");
        existing.setExternalId(SUBJECT);
        existing.setEnabled(false);
        when(userRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> provisioner.provision(
                principal(Map.of("sub", SUBJECT, "email", "jane@corp.example"))))
                .isInstanceOf(SsoProvisioningException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void theChangePasswordWallIsLiftedForSomebodyWhoJustProvedThemselvesToTheDirectory() {
        User existing = localUser("jane@corp.example", UserRole.USER, "temp-hash");
        existing.setMustChangePassword(true);
        when(userRepository.findByEmailIgnoreCase("jane@corp.example")).thenReturn(Optional.of(existing));

        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "email", "jane@corp.example")));

        // Behind that wall is a form asking for a temporary password they were never given.
        assertThat(user.isMustChangePassword()).isFalse();
    }

    @Test
    void theAddressIsTakenFromWhicheverConfiguredClaimCarriesIt() {
        // No `email`; this OTDS attribute mapping puts it in `upn`.
        User user = provisioner.provision(principal(Map.of("sub", SUBJECT, "upn", "jane@corp.example")));

        assertThat(user.getEmail()).isEqualTo("jane@corp.example");
    }

    @Test
    void anIdentityWithNoAddressAtAllIsRefusedWithTheClaimsThatWereTried() {
        assertThatThrownBy(() -> provisioner.provision(principal(Map.of("sub", SUBJECT))))
                .isInstanceOf(SsoProvisioningException.class)
                .hasMessageContainingAll("email", "preferred_username", "app.sso.email-claims");
    }

    @Test
    void theSubjectClaimCanBePointedSomewhereElse() {
        provisioner = new SsoUserProvisioner(userRepository,
                SsoPropertiesFixture.aProvider().userNameAttribute("oTExternalID3").build());

        User user = provisioner.provision(new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),
                Map.of("oTExternalID3", "cn=jane,ou=people", "sub", SUBJECT, "email", "jane@corp.example"),
                "oTExternalID3"));

        assertThat(user.getExternalId()).isEqualTo("cn=jane,ou=people");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static OAuth2User principal(Map<String, Object> claims) {
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OIDC_USER")), claims, "sub");
    }

    private static User localUser(String email, UserRole role, String passwordHash) {
        User user = new User();
        user.setEmail(email);
        user.setRole(role);
        user.setPassword(passwordHash);
        user.setAuthProvider(AuthProvider.LOCAL);
        return user;
    }

    private static User ssoUser(String email, String subject) {
        User user = new User();
        user.setEmail(email);
        user.setRole(UserRole.USER);
        user.setAuthProvider(AuthProvider.OTDS);
        user.setExternalId(subject);
        return user;
    }
}
