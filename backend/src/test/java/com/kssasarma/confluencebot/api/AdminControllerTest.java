package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.AdminUserRequest;
import com.kssasarma.confluencebot.api.dto.AdminUserResponse;
import com.kssasarma.confluencebot.email.EmailService;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRepository;
import com.kssasarma.confluencebot.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(userRepository, passwordEncoder, emailService);
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
    void createUser_emailSendSucceeds_reportsEmailSentTrue() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(emailService.sendWelcomeEmail(eq("new@example.com"), any())).thenReturn(true);

        ResponseEntity<?> response = controller.createUser(
                new AdminUserRequest("new@example.com", null, null), asAdmin("admin@example.com"));

        Map<String, Object> body = bodyOf(response);
        assertThat(body.get("emailSent")).isEqualTo(true);
    }

    @Test
    void createUser_emailSendFails_stillCreatesUserAndReportsEmailSentFalse() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(emailService.sendWelcomeEmail(eq("new@example.com"), any())).thenReturn(false);

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
}
