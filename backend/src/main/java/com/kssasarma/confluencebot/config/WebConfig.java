package com.kssasarma.confluencebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Browser origins allowed to call the API.
 *
 * CORS itself is applied once, by the Spring Security filter chain: registering a second policy on
 * the MVC layer only invites the two to disagree — which is how PATCH and DELETE ended up being
 * rejected while GET and POST worked.
 */
@Configuration
@ConfigurationProperties(prefix = "app.cors")
public class WebConfig {

    private List<String> allowedOrigins = List.of("*");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = (allowedOrigins == null || allowedOrigins.isEmpty())
                ? List.of("*")
                : allowedOrigins;
    }
}
