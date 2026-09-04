package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * An append-only record of an admin acting on a user account: created, resent a welcome email, or
 * deleted. {@link #targetUserId} is nulled (not cascaded) when the account it names is deleted, so
 * the event — the audit trail of that deletion included — outlives the row it points to.
 */
@Entity
@Table(name = "admin_user_events")
public class AdminUserEvent {

    public enum EventType { CREATED, RESENT, DELETED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private EventType eventType;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "target_email", nullable = false)
    private String targetEmail;

    /** Sorted, comma-joined role names the target held at the time of the event. */
    @Column
    private String roles;

    /** Null when the event has no email outcome to report (a deletion). */
    @Column(name = "email_sent")
    private Boolean emailSent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private static AdminUserEvent base(EventType eventType, String adminEmail, User target) {
        AdminUserEvent event = new AdminUserEvent();
        event.eventType = eventType;
        event.adminEmail = adminEmail;
        event.targetUserId = target.getId();
        event.targetEmail = target.getEmail();
        event.roles = String.join(",", UserRole.namesOf(target.getRoles()));
        return event;
    }

    public static AdminUserEvent of(EventType eventType, String adminEmail, User target, boolean emailSent) {
        AdminUserEvent event = base(eventType, adminEmail, target);
        event.emailSent = emailSent;
        return event;
    }

    public static AdminUserEvent deleted(String adminEmail, User target) {
        return base(EventType.DELETED, adminEmail, target);
    }

    public Long getId() { return id; }
    public EventType getEventType() { return eventType; }
    public String getAdminEmail() { return adminEmail; }
    public Long getTargetUserId() { return targetUserId; }
    public String getTargetEmail() { return targetEmail; }
    public String getRoles() { return roles; }
    public Boolean getEmailSent() { return emailSent; }
    public Instant getCreatedAt() { return createdAt; }
}
