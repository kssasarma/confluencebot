package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.SpaceSummaryResponse;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import io.swagger.v3.oas.annotations.Operation;
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

    private final ConfluencePageRepository pageRepository;

    public SpaceController(ConfluencePageRepository pageRepository) {
        this.pageRepository = pageRepository;
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
}
