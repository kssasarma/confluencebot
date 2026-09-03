package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false, unique = true)
    private String chatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column
    private String title;

    @Column(nullable = false)
    private boolean pinned = false;

    /**
     * True while the title is machine-derived and may still be replaced by a better summary.
     * A rename by the user clears it, which is what stops the async summariser from undoing
     * a deliberate choice a second later.
     */
    @Column(name = "title_generated", nullable = false)
    private boolean titleGenerated = false;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate() { this.updatedAt = Instant.now(); }

    /** Marks the conversation as active so it sorts to the top of the sidebar. */
    public void touch() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public User getUser() { return user; }
    public String getTitle() { return title; }
    public boolean isPinned() { return pinned; }
    public boolean isTitleGenerated() { return titleGenerated; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setChatId(String chatId) { this.chatId = chatId; }
    public void setUser(User user) { this.user = user; }
    /** A title the user chose. Never overwritten by the summariser. */
    public void setTitle(String title) {
        this.title = title;
        this.titleGenerated = false;
    }

    /** A title the system derived or summarised. Stays open to improvement. */
    public void setGeneratedTitle(String title) {
        this.title = title;
        this.titleGenerated = true;
    }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
}
