package com.kssasarma.confluencebot.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A suggested question shown on the welcome screen for a space, derived from what was actually
 * ingested rather than hard-coded in the frontend. Rows for a space are replaced wholesale by
 * {@code SuggestionGenerationService} every time that space is (re-)ingested, so there is nothing
 * here older than the content it was generated from.
 */
@Entity
@Table(name = "space_suggestions")
public class SpaceSuggestion {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_key", nullable = false)
    private String spaceKey;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SpaceSuggestion() {}

    public SpaceSuggestion(String spaceKey, String question) {
        this.spaceKey = spaceKey;
        this.question = question;
    }

    public Long getId() { return id; }
    public String getSpaceKey() { return spaceKey; }
    public String getQuestion() { return question; }
    public Instant getCreatedAt() { return createdAt; }
}
