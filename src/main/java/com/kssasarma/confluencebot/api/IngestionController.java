package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.IngestRequest;
import com.kssasarma.confluencebot.api.dto.IngestResponse;
import com.kssasarma.confluencebot.api.dto.PageSummaryResponse;
import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.ingestion.IngestionResult;
import com.kssasarma.confluencebot.ingestion.IngestionService;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Ingestion", description = "Trigger embedding and vector-store ingestion of Confluence content")
@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final IngestionService ingestionService;
    private final ConfluenceProperties props;
    private final ConfluencePageRepository pageRepository;

    public IngestionController(IngestionService ingestionService, ConfluenceProperties props,
                               ConfluencePageRepository pageRepository) {
        this.ingestionService = ingestionService;
        this.props = props;
        this.pageRepository = pageRepository;
    }

    @Operation(
            summary = "List ingested pages",
            description = """
                    Returns all Confluence pages currently recorded in the ingestion registry. \
                    Pass `spaceKey` to filter to a single space. Use this to verify what has been \
                    ingested, check version numbers, and inspect chunk counts without direct \
                    database access.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registry entries returned",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = PageSummaryResponse.class)),
                            examples = @ExampleObject(value = """
                                    [
                                      {
                                        "pageId": "131073",
                                        "spaceKey": "IT",
                                        "title": "Password Reset Guide",
                                        "url": "http://confluence.example.com/display/IT/Password+Reset+Guide",
                                        "version": 5,
                                        "chunkCount": 7,
                                        "ingestedAt": "2026-09-01T18:22:00Z"
                                      }
                                    ]
                                    """)))
    })
    @GetMapping("/pages")
    public ResponseEntity<List<PageSummaryResponse>> listPages(
            @Parameter(description = "Filter by Confluence space key. Omit to return all spaces.",
                    example = "IT")
            @RequestParam(required = false) String spaceKey) {

        List<PageSummaryResponse> pages = (spaceKey != null && !spaceKey.isBlank())
                ? pageRepository.findBySpaceKey(spaceKey).stream().map(PageSummaryResponse::from).toList()
                : pageRepository.findAll().stream().map(PageSummaryResponse::from).toList();

        return ResponseEntity.ok(pages);
    }

    @Operation(
            summary = "Ingest a Confluence space",
            description = """
                    Fetches all pages from the given space (or the configured default space when no \
                    body is sent), chunks them by heading, embeds each chunk, and upserts the vectors \
                    into the pgvector store. Pages whose Confluence version has not changed since the \
                    last run are skipped. A synthetic space-overview document is also created from the \
                    space description so broad questions about the space can be answered.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ingestion completed successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": "SUCCESS",
                                      "pagesProcessed": 47,
                                      "chunksStored": 312,
                                      "pagesSkipped": 3,
                                      "durationMs": 18420
                                    }
                                    """)))
    })
    @PostMapping("/space")
    public ResponseEntity<IngestResponse> ingestSpace(
            @RequestBody(required = false) IngestRequest request) {

        String spaceKey = (request != null && request.spaceKey() != null && !request.spaceKey().isBlank())
                ? request.spaceKey()
                : props.spaceKey();

        IngestionResult result = ingestionService.ingestSpace(spaceKey);
        return ResponseEntity.ok(toResponse(result));
    }

    @Operation(
            summary = "Re-ingest a single page",
            description = """
                    Fetches a single Confluence page by its numeric page ID, re-chunks and re-embeds \
                    it, and replaces the existing vectors in the store. Use this for targeted updates \
                    when a specific page changes and a full space re-ingest is not needed.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page re-ingested successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": "SUCCESS",
                                      "pagesProcessed": 1,
                                      "chunksStored": 7,
                                      "pagesSkipped": 0,
                                      "durationMs": 640
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Page not found in Confluence",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "type": "urn:confluencebot:error:not-found",
                                      "title": "Not Found",
                                      "status": 404,
                                      "detail": "Confluence page 999999 does not exist"
                                    }
                                    """)))
    })
    @PostMapping("/page/{pageId}")
    public ResponseEntity<IngestResponse> ingestPage(
            @Parameter(description = "Confluence numeric page ID", example = "131073")
            @PathVariable String pageId) {
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
