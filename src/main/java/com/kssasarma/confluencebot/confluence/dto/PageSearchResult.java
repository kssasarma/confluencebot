package com.kssasarma.confluencebot.confluence.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// Maps the paginated content search response from /rest/api/content
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageSearchResult(
        List<ConfluencePageDetail> results,
        int start,
        int limit,
        int size,
        Links _links
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(String next) {}
}
