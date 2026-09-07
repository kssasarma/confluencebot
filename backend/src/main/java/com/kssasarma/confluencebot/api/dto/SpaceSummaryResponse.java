package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.repository.ConfluencePageRepository.SpaceKeyName;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Confluence space that has at least one ingested page, for the chat space selector")
public record SpaceSummaryResponse(

        @Schema(description = "Confluence space key", example = "IT")
        String key,

        @Schema(description = "Human-readable space name, as of the last time one of its pages was "
                + "ingested. Falls back to the key when no name has been recorded yet.",
                example = "IT Support")
        String name
) {
    public static SpaceSummaryResponse from(SpaceKeyName row) {
        String name = row.getSpaceName();
        return new SpaceSummaryResponse(row.getSpaceKey(), (name == null || name.isBlank()) ? row.getSpaceKey() : name);
    }
}
