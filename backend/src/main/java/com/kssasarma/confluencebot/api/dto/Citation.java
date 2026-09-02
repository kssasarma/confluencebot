package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resolves one bracketed marker in the answer text to the page it came from.
 *
 * <p>The mapping has to be explicit because it is not positional: the model is shown one excerpt
 * per retrieved <em>chunk</em>, while {@code sources} carries one entry per <em>page</em>. Two
 * chunks of the same page produce two markers pointing at one source, so a client that paired
 * marker <i>n</i> with source <i>n</i> would mis-link the moment a page matched twice.
 */
@Schema(description = "Maps a bracketed marker in the answer, e.g. [2], to the page it cites")
public record Citation(
        @Schema(description = "The number inside the brackets in the answer text; 1-based", example = "2")
        int marker,

        @Schema(description = "Confluence page ID this marker refers to. Matches a SourceReference.pageId.",
                example = "131073")
        String pageId
) {}
