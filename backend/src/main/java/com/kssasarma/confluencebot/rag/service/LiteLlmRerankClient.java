package com.kssasarma.confluencebot.rag.service;

import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Client for LiteLLM's native {@code POST /rerank} endpoint. */
public class LiteLlmRerankClient implements RerankClient {

    private final RestClient restClient;
    private final String model;

    public LiteLlmRerankClient(RestClient.Builder builder, String baseUrl, String apiKey, String model) {
        this.restClient = builder.baseUrl(rerankUrl(baseUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
    }

    @Override
    public List<Integer> rerank(String query, List<String> documents) {
        RerankResponse response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RerankRequest(model, query, documents, documents.size()))
                .retrieve()
                .body(RerankResponse.class);
        if (response == null || response.results() == null) return List.of();
        return response.results().stream().map(RerankResult::index).toList();
    }

    static String rerankUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) throw new IllegalArgumentException("A re-rank endpoint URL is required");
        return baseUrl.replaceAll("/+$", "") + "/rerank";
    }

    record RerankRequest(String model, String query, List<String> documents, int top_n) {}
    record RerankResponse(List<RerankResult> results) {}
    record RerankResult(int index) {}
}
