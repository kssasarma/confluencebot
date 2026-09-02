package com.kssasarma.confluencebot.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kssasarma.confluencebot.api.dto.Citation;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts the JSON side-payloads of a chat message (sources, follow-up questions) to and from
 * their stored text form.
 *
 * Reading is deliberately lenient: a transcript that cannot deserialize its citations is still a
 * readable transcript, so a decode failure degrades to an empty list rather than failing the
 * whole history request.
 */
@Component
public class ChatMessagePayloadCodec {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagePayloadCodec.class);

    private static final TypeReference<List<SourceReference>> SOURCES = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<Citation>> CITATIONS = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ChatMessagePayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(List<?> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("Could not serialize chat message payload: {}", e.getMessage());
            return null;
        }
    }

    public List<SourceReference> readSources(String json) {
        return read(json, SOURCES);
    }

    public List<String> readStrings(String json) {
        return read(json, STRINGS);
    }

    public List<Citation> readCitations(String json) {
        return read(json, CITATIONS);
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Could not deserialize chat message payload: {}", e.getMessage());
            return List.of();
        }
    }
}
