package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.confluence.ConfluenceClient;
import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.SpaceMetadata;
import com.kssasarma.confluencebot.confluence.parser.ParsedSection;
import com.kssasarma.confluencebot.confluence.parser.StorageFormatParser;
import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import com.kssasarma.confluencebot.ingestion.chunking.ChunkingStrategy;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class IngestionServiceImpl implements IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionServiceImpl.class);

    private final ConfluenceClient confluenceClient;
    private final StorageFormatParser parser;
    private final ChunkingStrategy chunkingStrategy;
    private final VectorStore vectorStore;
    private final ConfluencePageRepository pageRepository;
    private final ConfluenceProperties props;
    private final JdbcTemplate jdbcTemplate;

    public IngestionServiceImpl(
            ConfluenceClient confluenceClient,
            StorageFormatParser parser,
            ChunkingStrategy chunkingStrategy,
            VectorStore vectorStore,
            ConfluencePageRepository pageRepository,
            ConfluenceProperties props,
            JdbcTemplate jdbcTemplate) {
        this.confluenceClient = confluenceClient;
        this.parser = parser;
        this.chunkingStrategy = chunkingStrategy;
        this.vectorStore = vectorStore;
        this.pageRepository = pageRepository;
        this.props = props;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public IngestionResult ingestSpace(String spaceKey) {
        long startMs = System.currentTimeMillis();
        log.info("Starting ingestion for space: {}", spaceKey);

        SpaceMetadata spaceMeta = confluenceClient.fetchSpaceMetadata(spaceKey);
        log.info("Space metadata fetched — name: '{}', description present: {}, homepage: {}",
                spaceMeta.name(), !spaceMeta.descriptionText().isBlank(), spaceMeta.homepageId());

        ingestSpaceOverview(spaceMeta);

        List<ConfluencePageDetail> pages = confluenceClient.fetchAllPages(spaceKey);

        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger totalChunks = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);

        for (ConfluencePageDetail page : pages) {
            try {
                Integer existingVersion = pageRepository.findVersionByPageId(page.id());

                if (existingVersion != null && existingVersion == page.version().number()) {
                    log.debug("Page {} ({}) unchanged at version {}, skipping",
                            page.title(), page.id(), existingVersion);
                    skipped.incrementAndGet();
                    continue;
                }

                int chunks = processPage(page, spaceKey, spaceMeta.name(), spaceMeta.homepageId());
                totalChunks.addAndGet(chunks);
                processed.incrementAndGet();

            } catch (Exception ex) {
                // A single failed page must NOT abort the entire space ingestion
                log.error("Failed to ingest page {} ({}): {}",
                        page.id(), page.title(), ex.getMessage(), ex);
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Ingestion complete — space: {}, processed: {}, chunks: {}, skipped: {}, time: {}ms",
                spaceKey, processed.get(), totalChunks.get(), skipped.get(), durationMs);

        return new IngestionResult(processed.get(), totalChunks.get(), skipped.get(), durationMs);
    }

    @Override
    @Transactional
    public IngestionResult ingestPage(String pageId) {
        long startMs = System.currentTimeMillis();
        SpaceMetadata spaceMeta = confluenceClient.fetchSpaceMetadata(props.spaceKey());
        ConfluencePageDetail page = confluenceClient.fetchPage(pageId);
        int chunks = processPage(page, props.spaceKey(), spaceMeta.name(), spaceMeta.homepageId());
        long durationMs = System.currentTimeMillis() - startMs;
        return new IngestionResult(1, chunks, 0, durationMs);
    }

    /**
     * Ingests the Confluence space description as a synthetic "space overview" document.
     * This gives broad queries like "What does the ENG space do?" a direct match target.
     * The old overview document (if any) is deleted first so re-ingestion is idempotent.
     */
    private void ingestSpaceOverview(SpaceMetadata spaceMeta) {
        String syntheticPageId = "__space__" + spaceMeta.key();
        deleteChunksForPage(syntheticPageId);

        String description = spaceMeta.descriptionText();
        if (description.isBlank()) {
            log.info("Space {} has no description — skipping space overview document", spaceMeta.key());
            return;
        }

        String content = "Space: %s (%s)\n\n%s".formatted(spaceMeta.name(), spaceMeta.key(), description);
        if (!spaceMeta.homepageTitle().isBlank()) {
            content += "\n\nHomepage: " + spaceMeta.homepageTitle();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("page_id", syntheticPageId);
        metadata.put("space_key", spaceMeta.key());
        metadata.put("space_name", spaceMeta.name());
        metadata.put("title", spaceMeta.name() + " — Space Overview");
        metadata.put("page_url", props.baseUrl() + "/display/" + spaceMeta.key());
        metadata.put("document_type", "space_overview");
        metadata.put("section_heading", "");
        metadata.put("chunk_index", 0);
        metadata.put("version", 0);

        vectorStore.add(List.of(new Document(content, metadata)));
        log.info("Space overview document ingested for space '{}'", spaceMeta.key());
    }

    /**
     * Template Method: fixed pipeline — delete → parse → chunk → embed → track.
     */
    private int processPage(ConfluencePageDetail page, String spaceKey,
                             String spaceName, String homepageId) {
        log.info("Processing page: {} [{}]", page.title(), page.id());

        deleteChunksForPage(page.id());

        String rawXhtml = Optional.ofNullable(page.body())
                .map(ConfluencePageDetail.Body::storage)
                .map(ConfluencePageDetail.Storage::value)
                .orElse("");

        List<ParsedSection> sections = parser.parse(rawXhtml);

        if (sections.isEmpty()) {
            log.warn("Page {} ({}) produced no parseable content — skipping", page.title(), page.id());
            return 0;
        }

        String pageUrl = buildPageUrl(page);
        boolean isHomepage = page.id().equals(homepageId);
        List<Document> documents = buildDocuments(sections, page, spaceKey, spaceName, pageUrl, isHomepage);

        if (documents.isEmpty()) {
            log.warn("Page {} ({}) produced no chunks after chunking — skipping", page.title(), page.id());
            return 0;
        }

        vectorStore.add(documents);

        upsertPageTracking(page, spaceKey, pageUrl, documents.size());

        log.info("Ingested: {} [{}] → {} chunks", page.title(), page.id(), documents.size());
        return documents.size();
    }

    /**
     * Deletes all vector store chunks for a page using direct SQL.
     * Uses the functional index on (metadata->>'page_id') for efficiency.
     */
    private void deleteChunksForPage(String pageId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM confluence_chunks WHERE metadata->>'page_id' = ?",
                pageId
        );
        log.debug("Deleted {} stale chunks for page {}", deleted, pageId);
    }

    /**
     * Processes each parsed section independently through the chunking strategy so that
     * the section's heading is preserved in chunk metadata.  This lets callers later
     * reconstruct a section-level anchor URL (page_url + "#" + section_heading).
     */
    private List<Document> buildDocuments(List<ParsedSection> sections, ConfluencePageDetail page,
                                           String spaceKey, String spaceName,
                                           String pageUrl, boolean isHomepage) {
        List<Document> docs = new ArrayList<>();
        int index = 0;

        for (ParsedSection section : sections) {
            if (!section.hasContent()) continue;

            // Reconstruct the full section text (heading + body) that the chunker receives
            String sectionText = section.hasHeading()
                    ? section.heading() + "\n" + section.content()
                    : section.content();

            List<String> chunks = chunkingStrategy.chunk(List.of(sectionText), page.title());

            for (String chunk : chunks) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("page_id", page.id());
                metadata.put("space_key", spaceKey);
                metadata.put("space_name", spaceName != null ? spaceName : "");
                metadata.put("title", page.title());
                metadata.put("page_url", pageUrl);
                metadata.put("chunk_index", index++);
                metadata.put("version", page.version().number());
                metadata.put("section_heading", section.hasHeading() ? section.heading() : "");
                metadata.put("is_homepage", String.valueOf(isHomepage));

                docs.add(new Document(chunk, metadata));
            }
        }

        return docs;
    }

    private void upsertPageTracking(ConfluencePageDetail page, String spaceKey,
                                     String pageUrl, int chunkCount) {
        ConfluencePageEntity entity = pageRepository.findById(page.id())
                .orElseGet(() -> ConfluencePageEntity.newPage(
                        page.id(), spaceKey, page.title(), pageUrl));

        entity.setVersion(page.version().number());
        entity.setChunkCount(chunkCount);
        entity.setIngestedAt(OffsetDateTime.now());
        pageRepository.save(entity);
    }

    private String buildPageUrl(ConfluencePageDetail page) {
        if (page._links() != null && page._links().webui() != null) {
            return props.baseUrl() + page._links().webui();
        }
        return props.baseUrl() + "/pages/viewpage.action?pageId=" + page.id();
    }
}
