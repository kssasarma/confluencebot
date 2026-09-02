package com.kssasarma.confluencebot.user;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A single persisted turn of a conversation.
 *
 * Sources and follow-up questions are stored as JSON text rather than as separate tables: they are
 * always read back with their message and are never queried on their own, so a child table would
 * buy nothing but joins.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private ChatSession session;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChatMessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "sources_json", columnDefinition = "TEXT")
    private String sourcesJson;

    @Column(name = "follow_ups_json", columnDefinition = "TEXT")
    private String followUpsJson;

    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    /**
     * How well retrieval matched the question, 0-1, or null for turns recorded before the score
     * existed. Null and zero mean different things, so the column stays nullable.
     */
    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public ChatSession getSession() { return session; }
    public int getSequenceNo() { return sequenceNo; }
    public ChatMessageRole getRole() { return role; }
    public String getContent() { return content; }
    public String getSourcesJson() { return sourcesJson; }
    public String getFollowUpsJson() { return followUpsJson; }
    public String getCitationsJson() { return citationsJson; }
    public Double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }

    public void setSession(ChatSession session) { this.session = session; }
    public void setSequenceNo(int sequenceNo) { this.sequenceNo = sequenceNo; }
    public void setRole(ChatMessageRole role) { this.role = role; }
    public void setContent(String content) { this.content = content; }
    public void setSourcesJson(String sourcesJson) { this.sourcesJson = sourcesJson; }
    public void setFollowUpsJson(String followUpsJson) { this.followUpsJson = followUpsJson; }
    public void setCitationsJson(String citationsJson) { this.citationsJson = citationsJson; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
}
