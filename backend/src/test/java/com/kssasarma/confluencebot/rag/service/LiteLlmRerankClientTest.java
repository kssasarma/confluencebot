package com.kssasarma.confluencebot.rag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiteLlmRerankClientTest {

    @Test
    void appendsTheNativeRerankPathWithoutDuplicatingSlashes() {
        assertThat(LiteLlmRerankClient.rerankUrl("https://proxy.example/v1"))
                .isEqualTo("https://proxy.example/v1/rerank");
        assertThat(LiteLlmRerankClient.rerankUrl("https://proxy.example/v1/"))
                .isEqualTo("https://proxy.example/v1/rerank");
    }
}
