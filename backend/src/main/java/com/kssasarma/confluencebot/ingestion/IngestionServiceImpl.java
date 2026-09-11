package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.confluence.ConfluenceClient;
import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.SpaceMetadata;
import com.kssasarma.confluencebot.confluence.parser.ParsedSection;
import com.kssasarma.confluencebot.confluence.parser.StorageFormatParser;
import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy.ChunkedContent;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
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
    private final SemanticChunkingStrategy chunkingStrategy;
    private final VectorStore vectorStore;
    private final ConfluencePageRepository pageRepository;
    private final ConfluenceProperties props;
    private final JdbcTemplate jdbcTemplate;

    public IngestionServiceImpl(
            ConfluenceClient confluenceClient,
            StorageFormatParser parser,
            SemanticChunkingStrategy chunkingStrategy,
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
    public IngestionResult ingestSpace(String spaceKey) {
        return ingestSpace(spaceKey, false);
    }

    @Override
    @Transactional
    public IngestionResult ingestSpace(String spaceKey, boolean force) {
        long startMs = System.currentTimeMillis();
        log.info("Starting ingestion for space: {} (force={})", spaceKey, force);

        SpaceMetadata spaceMeta = confluenceClient.fetchSpaceMetadata(spaceKey);
        log.info("Space metadata fetched — name: '{}', description present: {}, homepage: {}",
                spaceMeta.name(), !spaceMeta.descriptionText().isBlank(), spaceMeta.homepageId());

        ingestSpaceOverview(spaceMeta);

        List<ConfluencePageDetail> pages = confluenceClient.fetchAllPages(spaceKey);

        Set<String> fetchedPageIds = new HashSet<>();
        for (ConfluencePageDetail page : pages) {
            fetchedPageIds.add(page.id());
        }
        int removed = deleteRemovedPages(spaceKey, fetchedPageIds);

        AtomicInteger processed   = new AtomicInteger(0);
        AtomicInteger totalChunks = new AtomicInteger(0);
        AtomicInteger skipped     = new AtomicInteger(0);

        for (ConfluencePageDetail page : pages) {
            try {
                Integer existingVersion = pageRepository.findVersionByPageId(page.id());
                if (!force && existingVersion != null && existingVersion == page.version().number()) {
                    log.debug("Page {} ({}) unchanged at version {}, skipping",
                            page.title(), page.id(), existingVersion);
                    skipped.incrementAndGet();
                    continue;
                }

                int chunks = processPage(page, spaceKey, spaceMeta.name(), spaceMeta.homepageId());
                totalChunks.addAndGet(chunks);
                processed.incrementAndGet();

            } catch (Exception ex) {
                log.error("Failed to ingest page {} ({}): {}",
                        page.id(), page.title(), ex.getMessage(), ex);
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Ingestion complete — space: {}, processed: {}, chunks: {}, skipped: {}, removed: {}, time: {}ms",
                spaceKey, processed.get(), totalChunks.get(), skipped.get(), removed, durationMs);

        return new IngestionResult(processed.get(), totalChunks.get(), skipped.get(), durationMs);
    }

    /**
     * Pages tracked for this space that Confluence no longer returned are gone (deleted, moved,
     * or made inaccessible) — their stale chunks and tracking row are removed regardless of the
     * force flag, since this is about the fetched set going stale, not about re-embedding cost.
     *
     * <p>The diff-and-delete runs as a single atomic statement in the database (DELETE ... RETURNING)
     * rather than a SELECT followed by per-row {@code deleteById} calls: nothing here pulls the
     * space's full tracked-page list into the JVM (bounded by pages actually removed, not space
     * size), and there's no read-then-delete window for a concurrent ingest run on the same space
     * to race — a second run hitting an already-deleted row here would otherwise throw and roll
     * back its whole transaction. The chunk cleanup is similarly a single array delete rather than
     * one round trip per removed page.
     */
    private int deleteRemovedPages(String spaceKey, Set<String> currentPageIds) {
        String[] fetchedIds = currentPageIds.toArray(new String[0]);

        PreparedStatementSetter deletePagesPss = ps -> {
            ps.setString(1, spaceKey);
            ps.setArray(2, ps.getConnection().createArrayOf("varchar", fetchedIds));
        };
        List<String> removedIds = jdbcTemplate.query(
                "DELETE FROM confluence_pages WHERE space_key = ? AND page_id <> ALL (?) RETURNING page_id",
                deletePagesPss,
                (rs, rowNum) -> rs.getString("page_id"));

        if (removedIds.isEmpty()) {
            return 0;
        }

        String[] removedIdsArray = removedIds.toArray(new String[0]);
        PreparedStatementSetter deleteChunksPss = ps ->
                ps.setArray(1, ps.getConnection().createArrayOf("varchar", removedIdsArray));
        jdbcTemplate.update("DELETE FROM confluence_chunks WHERE metadata->>'page_id' = ANY (?)", deleteChunksPss);

        log.info("Removed {} page(s) no longer present in space {}: {}", removedIds.size(), spaceKey, removedIds);
        return removedIds.size();
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
        metadata.put("page_id",       syntheticPageId);
        metadata.put("space_key",     spaceMeta.key());
        metadata.put("space_name",    spaceMeta.name());
        metadata.put("title",         spaceMeta.name() + " — Space Overview");
        metadata.put("page_url",      props.baseUrl() + "/display/" + spaceMeta.key());
        metadata.put("document_type", "space_overview");
        metadata.put("section_heading", "");
        metadata.put("chunk_type",    "TEXT");
        metadata.put("chunk_index",   0);
        metadata.put("version",       0);

        vectorStore.add(List.of(new Document(content, metadata)));
        log.info("Space overview document ingested for space '{}'", spaceMeta.key());
    }

    private int processPage(ConfluencePageDetail page, String spaceKey,
                             String spaceName, String homepageId) {
        log.info("Processing page: {} [{}]", page.title(), page.id());

        // Build pageUrl before early-return paths so tracking is always possible.
        String pageUrl = buildPageUrl(page);

        deleteChunksForPage(page.id());

        String rawXhtml = Optional.ofNullable(page.body())
                .map(ConfluencePageDetail.Body::storage)
                .map(ConfluencePageDetail.Storage::value)
                .orElse("");

        List<ParsedSection> sections = parser.parse(rawXhtml);

        if (sections.isEmpty()) {
            log.warn("Page {} ({}) produced no parseable content — recorded with 0 chunks",
                    page.title(), page.id());
            upsertPageTracking(page, spaceKey, spaceName, pageUrl, 0);
            return 0;
        }

        boolean isHomepage = page.id().equals(homepageId);
        List<Document> documents = buildDocuments(sections, page, spaceKey, spaceName, pageUrl, isHomepage);

        if (documents.isEmpty()) {
            log.warn("Page {} ({}) produced no chunks — recorded with 0 chunks",
                    page.title(), page.id());
            upsertPageTracking(page, spaceKey, spaceName, pageUrl, 0);
            return 0;
        }

        vectorStore.add(documents);
        upsertPageTracking(page, spaceKey, spaceName, pageUrl, documents.size());

        log.info("Ingested: {} [{}] → {} chunks ({} sections)",
                page.title(), page.id(), documents.size(), sections.size());
        return documents.size();
    }

    private void deleteChunksForPage(String pageId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM confluence_chunks WHERE metadata->>'page_id' = ?", pageId);
        log.debug("Deleted {} stale chunks for page {}", deleted, pageId);
    }

    /**
     * Runs each ParsedSection through the SemanticChunkingStrategy, which handles TEXT/CODE/TABLE
     * sections with appropriate budgets and overlap.  chunk_type is stored in metadata so hybrid
     * search and prompt-building can use it downstream.
     */
    private List<Document> buildDocuments(List<ParsedSection> sections, ConfluencePageDetail page,
                                           String spaceKey, String spaceName,
                                           String pageUrl, boolean isHomepage) {
        List<Document> docs = new ArrayList<>();
        int index = 0;

        for (int i = 0; i < sections.size(); i++) {
            ParsedSection section = sections.get(i);
            if (!section.hasContent()) continue;

            List<ChunkedContent> chunks = section.isTable()
                    ? chunkingStrategy.chunk(section, page.title(), tableCaption(sections, i))
                    : chunkingStrategy.chunk(section, page.title());

            for (ChunkedContent chunk : chunks) {
                if (chunk.text() == null || chunk.text().isBlank()) continue;

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("page_id",         page.id());
                metadata.put("space_key",        spaceKey);
                metadata.put("space_name",       spaceName != null ? spaceName : "");
                metadata.put("title",            page.title());
                metadata.put("page_url",         pageUrl);
                metadata.put("chunk_index",      index++);
                metadata.put("version",          page.version().number());
                metadata.put("section_heading",  section.hasHeading() ? section.heading() : "");
                metadata.put("is_homepage",      String.valueOf(isHomepage));
                metadata.put("chunk_type",       chunk.chunkType());

                docs.add(new Document(chunk.text(), metadata));
            }
        }

        return docs;
    }

    /** Longest tail of the preceding paragraph folded into a table chunk's prefix — a full
     *  intro paragraph repeated on every split of a large table would eat into the row budget
     *  for little extra retrieval benefit over its last sentence or two. */
    private static final int TABLE_CAPTION_MAX_CHARS = 240;

    /**
     * The sentence immediately introducing a table ("The currently supported models are:") is
     * exactly the kind of narrative phrasing a natural-language question matches against — but
     * {@code JsoupStorageFormatParser} flushes it as its own TEXT section right before the table,
     * so a bare table chunk never sees it. Stitches that context back in at ingestion time so the
     * table doesn't have to rely on being rescued at retrieval time (see
     * {@code ReRankingService#ensureTableRepresented}) to be found at all.
     *
     * <p>Only the immediately preceding section counts, and only when it shares the table's
     * heading — text from a different subsection is as likely to mislead as to help. A table
     * that opens a page or a section has no caption, exactly as before this method existed.
     */
    private String tableCaption(List<ParsedSection> sections, int tableIndex) {
        if (tableIndex == 0) return "";

        ParsedSection previous = sections.get(tableIndex - 1);
        if (!previous.isText() || !previous.hasContent()) return "";

        String tableHeading = sections.get(tableIndex).heading();
        String previousHeading = previous.heading();
        boolean sameHeading = Objects.equals(
                tableHeading == null ? "" : tableHeading.strip(),
                previousHeading == null ? "" : previousHeading.strip());
        if (!sameHeading) return "";

        String text = previous.content().strip();
        if (text.length() <= TABLE_CAPTION_MAX_CHARS) return text;

        String tail = text.substring(text.length() - TABLE_CAPTION_MAX_CHARS);
        int firstSpace = tail.indexOf(' ');
        return firstSpace > 0 ? tail.substring(firstSpace + 1) : tail;
    }

    private void upsertPageTracking(ConfluencePageDetail page, String spaceKey, String spaceName,
                                     String pageUrl, int chunkCount) {
        ConfluencePageEntity entity = pageRepository.findById(page.id())
                .orElseGet(() -> ConfluencePageEntity.newPage(
                        page.id(), spaceKey, spaceName, page.title(), pageUrl));
        // Keeps the name current on re-ingestion (e.g. after a rename in Confluence), whether the
        // entity is the freshly-built one above or one already tracked from an earlier run.
        entity.setSpaceName(spaceName);
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
