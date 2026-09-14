package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.IngestionJobResponse;
import com.kssasarma.confluencebot.api.dto.IngestionScheduleRequest;
import com.kssasarma.confluencebot.api.dto.IngestionScheduleResponse;
import com.kssasarma.confluencebot.api.dto.UpdateIngestionScheduleRequest;
import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionScheduleEntity;
import com.kssasarma.confluencebot.schedule.IngestionScheduleService;
import com.kssasarma.confluencebot.schedule.command.CreateScheduleCommand;
import com.kssasarma.confluencebot.schedule.command.UpdateScheduleCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin – Ingestion Schedules",
        description = "Manage automatic re-ingestion schedules — admin only. "
                + "Schedules cause the system to re-crawl and re-embed a Confluence space "
                + "at a configurable interval without any manual intervention.")
@RestController
@RequestMapping("/api/admin/ingestion-schedules")
@PreAuthorize("hasRole('ADMIN')")
public class IngestionScheduleController {

    private final IngestionScheduleService scheduleService;

    public IngestionScheduleController(IngestionScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Operation(summary = "List all ingestion schedules",
            description = "Returns every configured auto-ingestion schedule, enabled or not.")
    @ApiResponse(responseCode = "200", description = "Schedule list returned",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IngestionScheduleResponse.class)))
    @GetMapping
    public ResponseEntity<List<IngestionScheduleResponse>> listSchedules() {
        List<IngestionScheduleResponse> schedules = scheduleService.findAll()
                .stream()
                .map(IngestionScheduleResponse::from)
                .toList();
        return ResponseEntity.ok(schedules);
    }

    @Operation(summary = "Get the ingestion schedule for a space",
            description = "Returns the auto-ingestion schedule configured for the given space key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestionScheduleResponse.class))),
            @ApiResponse(responseCode = "404", description = "No schedule for this space",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{spaceKey}")
    public ResponseEntity<IngestionScheduleResponse> getSchedule(
            @Parameter(description = "Confluence space key", example = "IT")
            @PathVariable String spaceKey) {

        return scheduleService.findBySpaceKey(spaceKey)
                .map(IngestionScheduleResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create or replace a space's ingestion schedule",
            description = """
                    Creates a new schedule for the given space, or replaces an existing one \
                    entirely. The first auto-run fires `intervalHours` after this call. \
                    If a schedule already exists for this space it is replaced; use PATCH to \
                    make partial changes without touching unrelated fields.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule created or replaced",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestionScheduleResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/{spaceKey}")
    public ResponseEntity<IngestionScheduleResponse> createOrReplace(
            @Parameter(description = "Confluence space key", example = "IT")
            @PathVariable String spaceKey,
            @Valid @RequestBody IngestionScheduleRequest request,
            Authentication auth) {

        CreateScheduleCommand command = new CreateScheduleCommand(
                request.intervalHours(), request.enabled(), request.force(), auth.getName());

        IngestionScheduleEntity saved = scheduleService.createOrReplace(spaceKey, command);
        return ResponseEntity.ok(IngestionScheduleResponse.from(saved));
    }

    @Operation(
            summary = "Partially update a space's ingestion schedule",
            description = """
                    Updates one or more fields of the schedule for the given space. \
                    Fields omitted from the request body are left unchanged. \
                    Use this to enable/disable or change the interval without touching other fields.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestionScheduleResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No schedule for this space",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping("/{spaceKey}")
    public ResponseEntity<IngestionScheduleResponse> updateSchedule(
            @Parameter(description = "Confluence space key", example = "IT")
            @PathVariable String spaceKey,
            @Valid @RequestBody UpdateIngestionScheduleRequest request,
            Authentication auth) {

        UpdateScheduleCommand command = new UpdateScheduleCommand(
                request.intervalHours(), request.enabled(), request.force(), auth.getName());

        IngestionScheduleEntity updated = scheduleService.update(spaceKey, command);
        return ResponseEntity.ok(IngestionScheduleResponse.from(updated));
    }

    @Operation(
            summary = "Delete a space's ingestion schedule",
            description = "Removes the auto-ingestion schedule. Any already-submitted jobs continue "
                    + "to run; only future automatic triggers are stopped.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Schedule deleted"),
            @ApiResponse(responseCode = "404", description = "No schedule for this space",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{spaceKey}")
    public ResponseEntity<Void> deleteSchedule(
            @Parameter(description = "Confluence space key", example = "IT")
            @PathVariable String spaceKey) {

        scheduleService.delete(spaceKey);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Trigger a scheduled ingestion now",
            description = """
                    Submits an immediate ingestion job for the space, using the force flag \
                    stored on the schedule. Behaves identically to a timer-driven run; the \
                    regular schedule continues unchanged. \
                    Returns 409 Conflict if an ingestion job for this space is already running.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Job submitted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestionJobResponse.class))),
            @ApiResponse(responseCode = "404", description = "No schedule for this space",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "A job for this space is already active",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{spaceKey}/trigger")
    public ResponseEntity<IngestionJobResponse> triggerNow(
            @Parameter(description = "Confluence space key", example = "IT")
            @PathVariable String spaceKey) {

        IngestionJobEntity job = scheduleService.triggerNow(spaceKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(IngestionJobResponse.from(job));
    }
}
