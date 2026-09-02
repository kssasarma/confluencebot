package com.kssasarma.confluencebot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI confluenceBotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Confluence RAG Chatbot API")
                        .description("""
                                REST API for a Retrieval-Augmented Generation (RAG) chatbot \
                                that embeds Confluence Server documentation into pgvector and \
                                answers questions from it using a locally-served LLM.

                                **Quick start**
                                1. `POST /api/ingest/space` — embed all pages in the configured space
                                2. `POST /api/chat` — ask a question and receive an answer with source citations
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("kssasarma")
                                .url("https://github.com/kssasarma/confluencebot"))
                        .license(new License()
                                .name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local / Docker")
                ));
    }
}
