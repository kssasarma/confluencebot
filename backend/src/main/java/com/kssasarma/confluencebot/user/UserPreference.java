package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_preferences")
public class UserPreference {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private String theme = "system";

    @Column(nullable = false)
    private String language = "en";

    @Column(name = "response_style", nullable = false)
    private String responseStyle = "balanced";

    @Column(name = "show_sources", nullable = false)
    private boolean showSources = true;

    @Column(name = "show_confidence", nullable = false)
    private boolean showConfidence = true;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTheme() { return theme; }
    public String getLanguage() { return language; }
    public String getResponseStyle() { return responseStyle; }
    public boolean isShowSources() { return showSources; }
    public boolean isShowConfidence() { return showConfidence; }

    public void setUser(User user) { this.user = user; }
    public void setTheme(String theme) { this.theme = theme; }
    public void setLanguage(String language) { this.language = language; }
    public void setResponseStyle(String responseStyle) { this.responseStyle = responseStyle; }
    public void setShowSources(boolean showSources) { this.showSources = showSources; }
    public void setShowConfidence(boolean showConfidence) { this.showConfidence = showConfidence; }
}
