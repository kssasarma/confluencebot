package com.kssasarma.confluencebot.auth;

import jakarta.validation.constraints.NotBlank;

/** The one-time code the browser was handed at the end of a single sign-on. */
public record SsoExchangeRequest(@NotBlank String code) {}
