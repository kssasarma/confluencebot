package com.kssasarma.confluencebot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "confluence_pages")
public class ConfluencePageEntity {

    @Id
    @Column(name = "page_id", length = 50)
    private String pageId;

    @Column(name = "space_key", length = 50, nullable = false)
    private String spaceKey;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "page_url")
    private String pageUrl;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "ingested_at", nullable = false)
    private OffsetDateTime ingestedAt;

    protected ConfluencePageEntity() {}

    public static ConfluencePageEntity newPage(String pageId, String spaceKey,
                                                String title, String pageUrl) {
        ConfluencePageEntity e = new ConfluencePageEntity();
        e.pageId = pageId;
        e.spaceKey = spaceKey;
        e.title = title;
        e.pageUrl = pageUrl;
        e.ingestedAt = OffsetDateTime.now();
        return e;
    }

    public String getPageId() { return pageId; }
    public String getSpaceKey() { return spaceKey; }
    public String getTitle() { return title; }
    public String getPageUrl() { return pageUrl; }
    public int getVersion() { return version; }
    public int getChunkCount() { return chunkCount; }
    public OffsetDateTime getIngestedAt() { return ingestedAt; }

    public void setVersion(int version) { this.version = version; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public void setIngestedAt(OffsetDateTime ingestedAt) { this.ingestedAt = ingestedAt; }
}
