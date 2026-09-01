package com.kssasarma.confluencebot.api.dto;

public record SourceReference(
        String pageId,
        String title,
        String url
) {}
