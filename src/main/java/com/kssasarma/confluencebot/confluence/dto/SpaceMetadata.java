package com.kssasarma.confluencebot.confluence.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpaceMetadata(
        String key,
        String name,
        SpaceDescription description,
        Homepage homepage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpaceDescription(Plain plain) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Plain(String value) {}

        public String text() {
            return plain != null && plain.value() != null ? plain.value().strip() : "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Homepage(String id, String title) {}

    public String descriptionText() {
        return description != null ? description.text() : "";
    }

    public String homepageId() {
        return homepage != null ? homepage.id() : null;
    }

    public String homepageTitle() {
        return homepage != null && homepage.title() != null ? homepage.title() : "";
    }
}
