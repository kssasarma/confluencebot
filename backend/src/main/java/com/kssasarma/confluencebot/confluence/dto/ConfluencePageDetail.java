package com.kssasarma.confluencebot.confluence.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Maps Confluence REST API response for a single page with body.storage,version,_links expanded
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluencePageDetail(
        String id,
        String title,
        Version version,
        Body body,
        Links _links
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Version(int number) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Storage storage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Storage(String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(String webui) {}
}
