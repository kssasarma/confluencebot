package com.kssasarma.confluencebot.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNameRequest(@NotBlank @Size(max = 255) String name) {

    /** Trims before validation runs, so padding whitespace can't push a name over the limit. */
    public UpdateNameRequest {
        name = name == null ? null : name.trim();
    }
}
