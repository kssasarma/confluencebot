package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
import com.kssasarma.confluencebot.config.ChatRetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ConfluencePromptBuilder promptBuilder;
    private final ChatRetrievalProperties retrievalProps;

    public ChatServiceImpl(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            ConfluencePromptBuilder promptBuilder,
            ChatRetrievalProperties retrievalProps) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.promptBuilder = promptBuilder;
        this.retrievalProps = retrievalProps;
    }

    @Override
    public ChatApiResponse chat(String query) {
        log.info("Chat query received: {}", query);

        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(retrievalProps.topK())
                        .similarityThreshold(retrievalProps.similarityThreshold())
                        .build()
        );

        if (relevantDocs.isEmpty()) {
            log.warn("No relevant documents found for query: {}", query);
            return ChatApiResponse.noContext();
        }

        log.debug("Retrieved {} relevant chunks", relevantDocs.size());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(promptBuilder.systemPrompt()),
                new UserMessage(promptBuilder.userPrompt(query, relevantDocs))
        ));

        String answer = chatClient.prompt(prompt)
                .call()
                .content();

        List<SourceReference> sources = extractSources(relevantDocs);

        return new ChatApiResponse(answer, sources);
    }

    /**
     * Builds one SourceReference per unique page from the retrieved chunks.
     * Docs arrive ordered by similarity score (highest first), so the first chunk
     * seen for each page_id is the best match — subsequent chunks for the same page
     * are skipped.  The section heading from that best chunk is used to construct
     * an anchor URL pointing directly to the relevant section on the Confluence page.
     */
    private List<SourceReference> extractSources(List<Document> docs) {
        LinkedHashMap<String, SourceReference> byPageId = new LinkedHashMap<>();

        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            String pageId = (String) meta.getOrDefault("page_id", "");

            if (byPageId.containsKey(pageId)) continue;

            String pageUrl = (String) meta.getOrDefault("page_url", "");
            if (pageUrl.isBlank()) {
                log.warn("Chunk for page_id={} is missing page_url metadata — citation URL will be empty", pageId);
            }

            String heading = (String) meta.getOrDefault("section_heading", "");
            String anchorUrl = buildAnchorUrl(pageUrl, heading);

            byPageId.put(pageId, new SourceReference(
                    pageId,
                    (String) meta.getOrDefault("title", "Unknown"),
                    pageUrl,
                    anchorUrl,
                    (String) meta.getOrDefault("space_key", ""),
                    doc.getScore()
            ));
        }

        return new ArrayList<>(byPageId.values());
    }

    private String buildAnchorUrl(String pageUrl, String heading) {
        if (pageUrl.isBlank() || heading.isBlank()) return pageUrl;
        // Confluence Server generates anchor IDs from the heading text with spaces as hyphens
        String anchor = heading.replace(" ", "-");
        return pageUrl + "#" + anchor;
    }
}
