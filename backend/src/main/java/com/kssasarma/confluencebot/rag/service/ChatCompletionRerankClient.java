package com.kssasarma.confluencebot.rag.service;

import com.kssasarma.confluencebot.prompt.PromptResources;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapts an ordinary chat-completions model to the {@link RerankClient} contract. */
public class ChatCompletionRerankClient implements RerankClient {

    private static final PromptTemplate SYSTEM_TEMPLATE = PromptResources.load("prompts/rerank/system.st");
    private static final PromptTemplate DOCUMENT_TEMPLATE = PromptResources.load("prompts/rerank/document.st");
    private static final PromptTemplate USER_TEMPLATE = PromptResources.load("prompts/rerank/user-message.st");
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[(.*?)\\]", Pattern.DOTALL);

    private final ChatClient chatClient;

    public ChatCompletionRerankClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<Integer> rerank(String query, List<String> documents) {
        String response = chatClient.prompt()
                .system(SYSTEM_TEMPLATE.render())
                .user(userPrompt(query, documents))
                .call()
                .content();
        return parseOrder(response, documents.size());
    }

    static String userPrompt(String query, List<String> documents) {
        StringBuilder documentsBlock = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            documentsBlock.append(DOCUMENT_TEMPLATE.render(Map.of(
                    "index", String.valueOf(index),
                    "content", documents.get(index))));
        }
        return USER_TEMPLATE.render(Map.of(
                "query", query,
                "documentsBlock", documentsBlock.toString()));
    }

    static List<Integer> parseOrder(String response, int documentCount) {
        if (response == null) throw new IllegalArgumentException("Chat re-rank returned no content");
        Matcher matcher = JSON_ARRAY.matcher(response);
        while (matcher.find()) {
            List<Integer> order = parseCandidate(matcher.group(1), documentCount);
            if (order != null) return order;
        }
        throw new IllegalArgumentException("Chat re-rank did not return a complete JSON index array");
    }

    /**
     * A chat model can mention a partial ordering before its final JSON answer. Each array is
     * considered until a complete, zero-based permutation is found.
     */
    private static List<Integer> parseCandidate(String candidate, int documentCount) {
        String values = candidate.trim();
        if (values.isEmpty() && documentCount == 0) return List.of();
        String[] parts = values.split("\\s*,\\s*");
        if (parts.length != documentCount) {
            return null;
        }

        List<Integer> order = new ArrayList<>(documentCount);
        boolean[] seen = new boolean[documentCount];
        for (String part : parts) {
            int index;
            try {
                index = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (index < 0 || index >= documentCount || seen[index]) {
                return null;
            }
            seen[index] = true;
            order.add(index);
        }
        return order;
    }
}
