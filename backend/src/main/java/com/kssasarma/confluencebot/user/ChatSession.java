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

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public User getUser() { return user; }
    public String getTitle() { return title; }
    public boolean isPinned() { return pinned; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setChatId(String chatId) { this.chatId = chatId; }
    public void setUser(User user) { this.user = user; }
    public void setTitle(String title) { this.title = title; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
}
