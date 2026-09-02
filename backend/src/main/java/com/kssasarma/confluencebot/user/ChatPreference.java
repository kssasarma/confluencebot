package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_preferences")
public class ChatPreference {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false, unique = true)
    private String chatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "response_style")
    private String responseStyle;

    @Column(name = "show_sources")
    private Boolean showSources;

    @Column(name = "show_confidence")
    private Boolean showConfidence;

    @Column(name = "custom_prompt", columnDefinition = "TEXT")
    private String customPrompt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getChatId() { return chatId; }
    public User getUser() { return user; }
    public String getResponseStyle() { return responseStyle; }
    public Boolean getShowSources() { return showSources; }
    public Boolean getShowConfidence() { return showConfidence; }
    public String getCustomPrompt() { return customPrompt; }

    public void setChatId(String chatId) { this.chatId = chatId; }
    public void setUser(User user) { this.user = user; }
    public void setResponseStyle(String responseStyle) { this.responseStyle = responseStyle; }
    public void setShowSources(Boolean showSources) { this.showSources = showSources; }
    public void setShowConfidence(Boolean showConfidence) { this.showConfidence = showConfidence; }
    public void setCustomPrompt(String customPrompt) { this.customPrompt = customPrompt; }
}
