package com.kssasarma.confluencebot.rag.service;

import java.util.List;

/** Calls a provider's native rerank endpoint and returns zero-based document indexes. */
public interface RerankClient {

    List<Integer> rerank(String query, List<String> documents);
}
