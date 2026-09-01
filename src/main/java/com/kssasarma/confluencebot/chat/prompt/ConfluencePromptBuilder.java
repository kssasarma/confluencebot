package com.kssasarma.confluencebot.chat.prompt;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ConfluencePromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You are a precise technical assistant that answers questions exclusively \
            from the provided Confluence documentation.

            Rules you MUST follow:
            1. Answer ONLY using information from the context provided below. \
               Do not use any prior knowledge or make up information.
            2. If the answer is not present in the context, respond exactly with: \
               "I could not find information about this in the documentation."
            3. At the end of every answer, list the source pages you used under \
               a "Sources:" heading with their titles and URLs.
            4. Keep answers accurate, concise, and well-structured.
            5. Use bullet points or numbered lists when listing steps or multiple items.
            """;

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String userPrompt(String query, List<Document> contextDocuments) {
        String context = contextDocuments.stream()
                .map(this::formatDocument)
                .collect(Collectors.joining("\n\n---\n\n"));

        return """
                === Confluence Documentation Context ===

                %s

                =========================================

                User question: %s

                Answer strictly using the context above.
                """.formatted(context, query);
    }

    private String formatDocument(Document doc) {
        String title = (String) doc.getMetadata().getOrDefault("title", "Unknown");
        String url = (String) doc.getMetadata().getOrDefault("page_url", "");
        return "[Source: %s | URL: %s]\n\n%s".formatted(title, url, doc.getText());
    }
}
