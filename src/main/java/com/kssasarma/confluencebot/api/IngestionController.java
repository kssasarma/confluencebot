package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.IngestRequest;
import com.kssasarma.confluencebot.api.dto.IngestResponse;
import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.ingestion.IngestionResult;
import com.kssasarma.confluencebot.ingestion.IngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final IngestionService ingestionService;
    private final ConfluenceProperties props;

    public IngestionController(IngestionService ingestionService, ConfluenceProperties props) {
        this.ingestionService = ingestionService;
        this.props = props;
    }

    /**
     * Ingest all pages from a Confluence space.
     * If no body is sent, defaults to the configured CONFLUENCE_SPACE_KEY.
     */
    @PostMapping("/space")
    public ResponseEntity<IngestResponse> ingestSpace(
            @RequestBody(required = false) IngestRequest request) {

        String spaceKey = (request != null && request.spaceKey() != null && !request.spaceKey().isBlank())
                ? request.spaceKey()
                : props.spaceKey();

        IngestionResult result = ingestionService.ingestSpace(spaceKey);
        return ResponseEntity.ok(toResponse(result));
    }

    /**
     * Re-ingest a single page by its Confluence pageId.
     * Use this when a specific page is updated and needs immediate re-embedding.
     */
    @PostMapping("/page/{pageId}")
    public ResponseEntity<IngestResponse> ingestPage(@PathVariable String pageId) {
        IngestionResult result = ingestionService.ingestPage(pageId);
        return ResponseEntity.ok(toResponse(result));
    }

    private IngestResponse toResponse(IngestionResult result) {
        return new IngestResponse(
                "SUCCESS",
                result.pagesProcessed(),
                result.chunksStored(),
                result.pagesSkipped(),
                result.durationMs()
        );
    }
}
