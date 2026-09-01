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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private List<SourceReference> extractSources(List<Document> docs) {
        return docs.stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata();
                    return new SourceReference(
                            (String) meta.getOrDefault("page_id", ""),
                            (String) meta.getOrDefault("title", "Unknown"),
                            (String) meta.getOrDefault("page_url", "")
                    );
                })
                .distinct()
                .collect(Collectors.toList());
    }
}
