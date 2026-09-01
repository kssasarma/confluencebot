package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.confluence.ConfluenceClient;
import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
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

                int chunks = processPage(page, spaceKey);
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
        ConfluencePageDetail page = confluenceClient.fetchPage(pageId);
        int chunks = processPage(page, props.spaceKey());
        long durationMs = System.currentTimeMillis() - startMs;
        return new IngestionResult(1, chunks, 0, durationMs);
    }

    /**
     * Template Method: fixed pipeline — delete → parse → chunk → embed → track.
     */
    private int processPage(ConfluencePageDetail page, String spaceKey) {
        log.info("Processing page: {} [{}]", page.title(), page.id());

        deleteChunksForPage(page.id());

        String rawXhtml = Optional.ofNullable(page.body())
                .map(ConfluencePageDetail.Body::storage)
                .map(ConfluencePageDetail.Storage::value)
                .orElse("");

        List<String> sections = parser.parse(rawXhtml);

        if (sections.isEmpty()) {
            log.warn("Page {} ({}) produced no parseable content — skipping", page.title(), page.id());
            return 0;
        }

        List<String> chunks = chunkingStrategy.chunk(sections, page.title());

        String pageUrl = buildPageUrl(page);
        List<Document> documents = buildDocuments(chunks, page, spaceKey, pageUrl);

        vectorStore.add(documents);

        upsertPageTracking(page, spaceKey, pageUrl, chunks.size());

        log.info("Ingested: {} [{}] → {} chunks", page.title(), page.id(), chunks.size());
        return chunks.size();
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

    private List<Document> buildDocuments(List<String> chunks, ConfluencePageDetail page,
                                           String spaceKey, String pageUrl) {
        List<Document> docs = new ArrayList<>();
        int index = 0;

        for (String chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("page_id", page.id());
            metadata.put("space_key", spaceKey);
            metadata.put("title", page.title());
            metadata.put("page_url", pageUrl);
            metadata.put("chunk_index", index++);
            metadata.put("version", page.version().number());

            docs.add(new Document(chunk, metadata));
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
