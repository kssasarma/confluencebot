package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.IngestRequest;
import com.kssasarma.confluencebot.api.dto.IngestionJobResponse;
import com.kssasarma.confluencebot.api.dto.PageSummaryResponse;
import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.ingestion.IngestionJobService;
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
import java.util.UUID;

@Tag(name = "Ingestion", description = "Trigger embedding and vector-store ingestion of Confluence content")
@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private final IngestionJobService jobService;
    private final ConfluenceProperties props;
    private final ConfluencePageRepository pageRepository;

    public IngestionController(IngestionJobService jobService, ConfluenceProperties props,
                               ConfluencePageRepository pageRepository) {
        this.jobService = jobService;
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
            summary = "Submit a space ingestion job",
            description = """
                    Submits a background job to fetch all pages from the given space (or the \
                    configured default space when no body is sent), chunk them, embed each chunk, \
                    and upsert the vectors into the pgvector store. Returns immediately with a job \
                    ID that can be polled via GET /api/ingest/jobs/{jobId}. Pages whose Confluence \
                    version has not changed since the last run are skipped unless `force` is true.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Job accepted and queued",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestionJobResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "jobId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                                      "jobType": "SPACE",
                                      "spaceKey": "IT",
                                      "pageId": null,
                                      "force": false,
                                      "status": "PENDING",
                                      "createdAt": "2026-09-02T10:00:00Z",
                                      "startedAt": null,
                                      "completedAt": null,
                                      "pagesProcessed": null,
                                      "chunksStored": null,
                                      "pagesSkipped": null,
                                      "errorMessage": null
                                    }
                                    """)))
    })
    @PostMapping("/space")
    public ResponseEntity<IngestionJobResponse> ingestSpace(
            @RequestBody(required = false) IngestRequest request) {

        String spaceKey = (request != null && request.spaceKey() != null && !request.spaceKey().isBlank())
                ? request.spaceKey()
                : props.spaceKey();
        boolean force = request != null && request.isForce();

        IngestionJobEntity job = jobService.submitSpaceJob(spaceKey, force);
        return ResponseEntity.accepted().body(IngestionJobResponse.from(job));
    }

    @Operation(
            summary = "Submit a single-page ingestion job",
            description = """
                    Submits a background job to re-chunk and re-embed a single Confluence page \
                    by its numeric page ID, replacing the existing vectors in the store. Returns \
                    immediately with a job ID. Use GET /api/ingest/jobs/{jobId} to poll the result.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Job accepted and queued",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestionJobResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "jobId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
                                      "jobType": "PAGE",
                                      "spaceKey": null,
                                      "pageId": "131073",
                                      "force": false,
                                      "status": "PENDING",
                                      "createdAt": "2026-09-02T10:00:00Z",
                                      "startedAt": null,
                                      "completedAt": null,
                                      "pagesProcessed": null,
                                      "chunksStored": null,
                                      "pagesSkipped": null,
                                      "errorMessage": null
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
    public ResponseEntity<IngestionJobResponse> ingestPage(
            @Parameter(description = "Confluence numeric page ID", example = "131073")
            @PathVariable String pageId) {

        IngestionJobEntity job = jobService.submitPageJob(pageId);
        return ResponseEntity.accepted().body(IngestionJobResponse.from(job));
    }

    @Operation(
            summary = "Get ingestion job status",
            description = "Returns the current status and result (once complete) of a background ingestion job.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestionJobResponse.class))),
            @ApiResponse(responseCode = "404", description = "Job not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<IngestionJobResponse> getJob(
            @Parameter(description = "Job UUID returned by the submit endpoints")
            @PathVariable UUID jobId) {

        return jobService.findById(jobId)
                .map(IngestionJobResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "List all ingestion jobs",
            description = "Returns all ingestion jobs, newest first. Use this to monitor running jobs or review history.")
    @ApiResponse(responseCode = "200", description = "Job list returned",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = IngestionJobResponse.class))))
    @GetMapping("/jobs")
    public ResponseEntity<List<IngestionJobResponse>> listJobs() {
        List<IngestionJobResponse> jobs = jobService.findAll().stream()
                .map(IngestionJobResponse::from)
                .toList();
        return ResponseEntity.ok(jobs);
    }
}
