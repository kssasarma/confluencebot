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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A user can now hold several roles at once; these pin the endpoints that create and re-assign
 * them, including the self-demotion guard that stops an admin locking themselves out.
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private AdminUserEventRepository eventRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(userRepository, passwordEncoder, emailService, eventRepository, refreshTokenRepository);
    }

    private static Authentication asAdmin(String email) {
        return new UsernamePasswordAuthenticationToken(email, null);
    }

    private static User userWithRoles(Long id, String email, Set<UserRole> roles) {
        User user = new User();
        user.setEmail(email);
        user.setRoles(roles);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @SuppressWarnings("unchecked")
    private static <T> T bodyOf(ResponseEntity<?> response) {
        return (T) response.getBody();
    }

    // ── createUser ───────────────────────────────────────────────────────────

    @Test
    void createUser_noRolesGiven_defaultsToUser() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("new@example.com", null, null), asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = bodyOf(response);
        AdminUserResponse created = (AdminUserResponse) body.get("user");
        assertThat(created.roles()).containsExactly("USER");
    }

    @Test
    void createUser_explicitMultipleRoles_persistsAllOfThem() {
        when(userRepository.existsByEmail("ingestor@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("ingestor@example.com", List.of("INGESTOR", "USER"), null),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = bodyOf(response);
        AdminUserResponse created = (AdminUserResponse) body.get("user");
        assertThat(created.roles()).containsExactlyInAnyOrder("INGESTOR", "USER");
    }

    @Test
    void createUser_emailSendSucceeds_reportsEmailSentTrueAndRecordsEvent() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(emailService.sendWelcomeEmail(eq("new@example.com"), any(), any())).thenReturn(true);

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("new@example.com", null, null), asAdmin("admin@example.com"));

        Map<String, Object> body = bodyOf(response);
        assertThat(body.get("emailSent")).isEqualTo(true);

        ArgumentCaptor<AdminUserEvent> event = ArgumentCaptor.forClass(AdminUserEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AdminUserEvent.EventType.CREATED);
        assertThat(event.getValue().getAdminEmail()).isEqualTo("admin@example.com");
        assertThat(event.getValue().getTargetEmail()).isEqualTo("new@example.com");
        assertThat(event.getValue().getEmailSent()).isTrue();
    }

    @Test
    void createUser_ccsTheOnboardingAdminOnTheWelcomeEmail() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.createUser(new AdminUserRequest("new@example.com", null, null), asAdmin("admin@example.com"));

        verify(emailService).sendWelcomeEmail(eq("new@example.com"), eq("admin@example.com"), any());
    }

    @Test
    void createUser_emailSendFails_stillCreatesUserAndReportsEmailSentFalse() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(emailService.sendWelcomeEmail(eq("new@example.com"), any(), any())).thenReturn(false);

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("new@example.com", null, null), asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = bodyOf(response);
        assertThat(body.get("emailSent")).isEqualTo(false);
        assertThat(body.get("tempPassword")).isNotNull();
    }

    @Test
    void createUser_invalidRole_returnsBadRequestAndNeverSaves() {
        when(userRepository.existsByEmail("bad@example.com")).thenReturn(false);

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("bad@example.com", List.of("SUPERUSER"), null),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_nullRoleInList_returnsBadRequestRatherThanCrashing() {
        when(userRepository.existsByEmail("bad@example.com")).thenReturn(false);

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("bad@example.com", Arrays.asList("ADMIN", null), null),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_emailAlreadyInUse_returnsConflict() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("dup@example.com", null, null), asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(userRepository, never()).save(any());
    }

    // ── listUsers ────────────────────────────────────────────────────────────

    @Test
    void listUsers_mapsEveryPersistedUser() {
        when(userRepository.findAll()).thenReturn(List.of(
                userWithRoles(1L, "a@example.com", Set.of(UserRole.USER)),
                userWithRoles(2L, "b@example.com", Set.of(UserRole.ADMIN, UserRole.INGESTOR))));

        ResponseEntity<List<AdminUserResponse>> response = controller.listUsers();

        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(1).roles()).containsExactlyInAnyOrder("ADMIN", "INGESTOR");
    }

    // ── setEnabled ───────────────────────────────────────────────────────────

    @Test
    void setEnabled_disablingSelf_isRefused() {
        User self = userWithRoles(1L, "admin@example.com", Set.of(UserRole.ADMIN));
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        ResponseEntity<?> response = controller.setEnabled(1L, Map.of("enabled", false),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
    }

    @Test
    void setEnabled_disablingSomeoneElse_succeeds() {
        User other = userWithRoles(2L, "other@example.com", Set.of(UserRole.USER));
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.save(other)).thenReturn(other);

        ResponseEntity<?> response = controller.setEnabled(2L, Map.of("enabled", false),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.isEnabled()).isFalse();
    }

    @Test
    void setEnabled_unknownUser_returnsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.setEnabled(99L, Map.of("enabled", true),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── updateRoles ──────────────────────────────────────────────────────────

    @Test
    void updateRoles_emptyList_returnsBadRequest() {
        ResponseEntity<?> response = controller.updateRoles(2L, Map.of("roles", List.of()),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void updateRoles_missingKey_returnsBadRequest() {
        ResponseEntity<?> response = controller.updateRoles(2L, Map.of(), asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateRoles_nullRoleInList_returnsBadRequestRatherThanCrashing() {
        ResponseEntity<?> response = controller.updateRoles(2L, Map.of("roles", Arrays.asList("USER", null)),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void updateRoles_invalidRoleName_returnsBadRequest() {
        ResponseEntity<?> response = controller.updateRoles(2L, Map.of("roles", List.of("SUPERUSER")),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void updateRoles_adminRemovesOwnAdminRole_isRefused() {
        User self = userWithRoles(1L, "admin@example.com", Set.of(UserRole.ADMIN));
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        ResponseEntity<?> response = controller.updateRoles(1L, Map.of("roles", List.of("USER")),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
        assertThat(self.getRoles()).containsExactly(UserRole.ADMIN);
    }

    @Test
    void updateRoles_adminKeepsAdminAlongsideANewRole_isAllowed() {
        User self = userWithRoles(1L, "admin@example.com", Set.of(UserRole.ADMIN));
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));
        when(userRepository.save(self)).thenReturn(self);

        ResponseEntity<?> response = controller.updateRoles(1L,
                Map.of("roles", List.of("ADMIN", "INGESTOR")), asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(self.getRoles()).containsExactlyInAnyOrder(UserRole.ADMIN, UserRole.INGESTOR);
    }

    @Test
    void updateRoles_grantingIngestorToAnotherUser_succeeds() {
        User other = userWithRoles(2L, "other@example.com", Set.of(UserRole.USER));
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.save(other)).thenReturn(other);

        ResponseEntity<?> response = controller.updateRoles(2L,
                Map.of("roles", List.of("USER", "INGESTOR")), asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AdminUserResponse updated = bodyOf(response);
        assertThat(updated.roles()).containsExactlyInAnyOrder("USER", "INGESTOR");
    }

    @Test
    void updateRoles_unknownUser_returnsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.updateRoles(99L, Map.of("roles", List.of("USER")),
                asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Guards against {@link ProblemDetail} bodies silently disappearing from a bad-request path. */
    @Test
    void updateRoles_invalidRoleName_bodyExplainsWhichValueWasRejected() {
        ResponseEntity<?> response = controller.updateRoles(2L, Map.of("roles", List.of("SUPERUSER")),
                asAdmin("admin@example.com"));

        ProblemDetail detail = bodyOf(response);
        assertThat(detail.getDetail()).contains("SUPERUSER");
    }

    // ── resendWelcome ────────────────────────────────────────────────────────

    @Test
    void resendWelcome_existingUser_issuesFreshPasswordAndRecordsEvent() {
        User other = userWithRoles(2L, "other@example.com", Set.of(UserRole.USER));
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(passwordEncoder.encode(any())).thenReturn("hashed-new");
        when(userRepository.save(other)).thenReturn(other);
        when(emailService.sendWelcomeEmail(eq("other@example.com"), eq("admin@example.com"), any())).thenReturn(true);

        ResponseEntity<?> response = controller.resendWelcome(2L, asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.isMustChangePassword()).isTrue();
        Map<String, Object> body = bodyOf(response);
        assertThat(body.get("emailSent")).isEqualTo(true);
        assertThat(body.get("tempPassword")).isNotNull();

        ArgumentCaptor<AdminUserEvent> event = ArgumentCaptor.forClass(AdminUserEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AdminUserEvent.EventType.RESENT);

        // The old password must stop working the moment a new one is generated — any session
        // still alive under it should not survive a resend, same as changePassword enforces.
        verify(refreshTokenRepository).revokeAllByUserId(2L);
    }

    @Test
    void resendWelcome_unknownUser_returnsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.resendWelcome(99L, asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(emailService, never()).sendWelcomeEmail(any(), any(), any());
    }

    // ── deleteUser ───────────────────────────────────────────────────────────

    @Test
    void deleteUser_deletingSelf_isRefused() {
        User self = userWithRoles(1L, "admin@example.com", Set.of(UserRole.ADMIN));
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        ResponseEntity<?> response = controller.deleteUser(1L, asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).delete(any());
    }

    @Test
    void deleteUser_deletingSomeoneElse_cascadesAndRecordsEvent() {
        User other = userWithRoles(2L, "other@example.com", Set.of(UserRole.USER));
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));

        ResponseEntity<?> response = controller.deleteUser(2L, asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(userRepository).delete(other);

        ArgumentCaptor<AdminUserEvent> event = ArgumentCaptor.forClass(AdminUserEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AdminUserEvent.EventType.DELETED);
        assertThat(event.getValue().getTargetEmail()).isEqualTo("other@example.com");
    }

    @Test
    void deleteUser_unknownUser_returnsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteUser(99L, asAdmin("admin@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(userRepository, never()).delete(any());
    }

    // ── listAuditEvents ──────────────────────────────────────────────────────

    @Test
    void listAuditEvents_returnsEventsMostRecentFirst() {
        User target = userWithRoles(2L, "other@example.com", Set.of(UserRole.USER));
        AdminUserEvent event = AdminUserEvent.of(AdminUserEvent.EventType.CREATED, "admin@example.com", target, true);
        when(eventRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(event)));

        ResponseEntity<List<AdminUserEventResponse>> response = controller.listAuditEvents(0, 50);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).targetEmail()).isEqualTo("other@example.com");
    }
}
