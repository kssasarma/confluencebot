package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.SpaceSummaryResponse;
import com.kssasarma.confluencebot.domain.SpaceSuggestion;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import com.kssasarma.confluencebot.repository.SpaceSuggestionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Lists the Confluence spaces available to scope chat search to.
 *
 * <p>Every signed-in user can read the full list: there is no space-level access control today
 * (everyone can search everything), so this deliberately has no {@code @PreAuthorize} beyond the
 * blanket "must be authenticated" rule already applied to every endpoint. Narrowing this to a
 * per-user or per-role accessible set is future work, once space-level permissions exist.
 */
@Tag(name = "Spaces", description = "List Confluence spaces available to scope chat search to")
@RestController
@RequestMapping("/api/spaces")
public class SpaceController {

    /** Suggestions capped here, not just at generation time: a space ingested before this cap
     * existed, or one whose rows were hand-edited, should not flood the welcome screen either. */
    private static final int MAX_SUGGESTIONS = 4;

    private final ConfluencePageRepository pageRepository;
    private final SpaceSuggestionRepository suggestionRepository;

    public SpaceController(ConfluencePageRepository pageRepository, SpaceSuggestionRepository suggestionRepository) {
        this.pageRepository = pageRepository;
        this.suggestionRepository = suggestionRepository;
    }

    @Operation(
            summary = "List spaces available to search",
            description = """
                    Returns every Confluence space with at least one ingested page, sorted by \
                    display name. Pass a space's `key` as `spaceKey` on POST /api/chat or \
                    /api/chat/stream to scope retrieval to that space only.
                    """)
    @ApiResponse(responseCode = "200", description = "Spaces returned",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = SpaceSummaryResponse.class)),
                    examples = @ExampleObject(value = """
                            [
                              { "key": "IT", "name": "IT Support" },
                              { "key": "ENG", "name": "Engineering" }
                            ]
                            """)))
    @GetMapping
    public ResponseEntity<List<SpaceSummaryResponse>> listSpaces() {
        List<SpaceSummaryResponse> spaces = pageRepository.findDistinctSpaces().stream()
                .map(SpaceSummaryResponse::from)
                .sorted(Comparator.comparing(SpaceSummaryResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return ResponseEntity.ok(spaces);
    }

    @Operation(
            summary = "Suggested questions for the welcome screen",
            description = """
                    Returns up to four example questions generated from what was actually ingested \
                    — see SuggestionGenerationService, which (re)writes them every time a space \
                    finishes ingesting. Pass `spaceKey` to scope to one space; omit it (or when the \
                    space has none yet) for a random sample drawn across every space that has any.
                    """)
    @ApiResponse(responseCode = "200", description = "Suggestions returned",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = String.class)),
                    examples = @ExampleObject(value = """
                            ["How do I request VPN access?", "Where is the on-call rotation documented?"]
                            """)))
    @GetMapping("/suggestions")
    public ResponseEntity<List<String>> suggestions(
            @Parameter(description = "Confluence space key to scope suggestions to. Omit for a "
                    + "cross-space sample.", example = "IT")
            @RequestParam(required = false) String spaceKey) {

        List<SpaceSuggestion> rows = (spaceKey != null && !spaceKey.isBlank())
                ? suggestionRepository.findBySpaceKeyOrderByIdAsc(spaceKey)
                : suggestionRepository.findRandomSample(MAX_SUGGESTIONS);

        List<String> questions = rows.stream().map(SpaceSuggestion::getQuestion)
                .limit(MAX_SUGGESTIONS)
                .toList();

        return ResponseEntity.ok(questions);
    }
}
