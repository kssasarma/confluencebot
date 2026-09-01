package com.kssasarma.confluencebot.chat.prompt;

import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the LLM prompt for the hybrid-RAG chat pipeline.
 *
 * The prompt embeds the retrieved chunks as numbered sources, adds a confidence caveat when the
 * top similarity score is below the threshold, and instructs the LLM to append 3 follow-up
 * questions after a fixed marker so they can be parsed and surfaced separately.
 */
@Component
public class ConfluencePromptBuilder {

    static final String FOLLOW_UP_MARKER = "---FOLLOW-UP-QUESTIONS---";

    /**
     * Builds the full prompt text ready to send to the LLM.
     *
     * @param query         the user's question
     * @param chunks        re-ranked retrieval results (most relevant first)
     * @param lowConfidence true when the top cosine score is below the configured threshold
     */
    public String buildPrompt(String query, List<RetrievedChunk> chunks, boolean lowConfidence) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a precise technical assistant that answers questions exclusively ")
              .append("from the provided Confluence documentation.\n\n");

        prompt.append("Rules:\n")
              .append("1. Answer ONLY using information from the documentation excerpts below.\n")
              .append("2. Do not invent or extrapolate facts not present in the excerpts.\n")
              .append("3. Use bullet points or numbered lists when listing steps or multiple items.\n")
              .append("4. Cite the source page title in square brackets when referring to a specific fact.\n");

        if (lowConfidence) {
            prompt.append("\nIMPORTANT: These excerpts are only a weak match for the question — they may be ")
                  .append("tangential or only partially relevant. If they don't actually answer the question, ")
                  .append("say so explicitly and describe what they DO cover instead of presenting a weak ")
                  .append("match as a confident answer.\n");
        }

        prompt.append("\n=== Confluence Documentation Excerpts ===\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            prompt.append('[').append(i + 1).append("] ");
            if (chunk.getTitle() != null && !chunk.getTitle().isBlank()) {
                prompt.append("Source: ").append(chunk.getTitle());
                if (chunk.getSectionHeading() != null && !chunk.getSectionHeading().isBlank()) {
                    prompt.append(" › ").append(chunk.getSectionHeading());
                }
                prompt.append('\n');
            }
            if ("CODE".equals(chunk.getChunkType())) {
                prompt.append("(Code excerpt)\n");
            } else if ("TABLE".equals(chunk.getChunkType())) {
                prompt.append("(Table)\n");
            }
            prompt.append(chunk.getContent()).append("\n\n");
        }

        prompt.append("==========================================\n\n");
        prompt.append("User question: ").append(query).append("\n\n");
        prompt.append("Provide a clear, accurate answer based solely on the documentation. ")
              .append("If the documentation does not contain enough information, say so explicitly.\n\n");
        prompt.append("After your answer, on a new line write exactly: ").append(FOLLOW_UP_MARKER).append("\n");
        prompt.append("Then provide exactly 3 short follow-up questions the user might ask next, ")
              .append("one per line, no numbering or bullets.");

        return prompt.toString();
    }

    // ── Legacy Spring AI Document-based overload (kept for backward compatibility) ──────────────

    /** @deprecated Use {@link #buildPrompt(String, List, boolean)} with RetrievedChunk instead. */
    @Deprecated
    public String systemPrompt() {
        return "You are a precise technical assistant that answers questions exclusively from the provided Confluence documentation.";
    }

    /** @deprecated Use {@link #buildPrompt(String, List, boolean)} with RetrievedChunk instead. */
    @Deprecated
    public String userPrompt(String query, List<org.springframework.ai.document.Document> contextDocuments) {
        StringBuilder context = new StringBuilder();
        for (org.springframework.ai.document.Document doc : contextDocuments) {
            String title = (String) doc.getMetadata().getOrDefault("title", "Unknown");
            String url   = (String) doc.getMetadata().getOrDefault("page_url", "");
            context.append("[Source: ").append(title).append(" | URL: ").append(url)
                   .append("]\n\n").append(doc.getText()).append("\n\n---\n\n");
        }
        return "=== Confluence Documentation Context ===\n\n" + context
               + "=========================================\n\nUser question: " + query
               + "\n\nAnswer strictly using the context above.";
    }
}
