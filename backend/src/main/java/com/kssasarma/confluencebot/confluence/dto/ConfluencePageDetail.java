package com.kssasarma.confluencebot.confluence.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Maps Confluence REST API response for a single page with body.storage,version,_links expanded.
// The `space` field is returned by the Confluence API at the top level without explicit expansion.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluencePageDetail(
        String id,
        String title,
        Version version,
        Body body,
        Links _links,
        Space space
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Version(int number) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Storage storage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Storage(String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(String webui) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Space(String key, String name) {}
}
