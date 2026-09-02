package com.kssasarma.confluencebot.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "chat.retrieval")
public record ChatRetrievalProperties(
        /** Final number of chunks sent to the LLM. */
        @Positive int topK,

        /** Minimum cosine similarity for the top-ranked chunk — below this a confidence caveat
         *  is added to the prompt.  Not a hard filter; all candidates from hybrid search are still
         *  used, but the LLM is told the match is weak. */
        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,

        /** Candidate pool size for both dense and lexical retrieval before RRF fusion.
         *  Should be considerably larger than topK so the re-ranker has room to work. */
        @Positive int candidatePoolSize,

        /** Token budget per chunk; roughly 4 chars/token. */
        @Positive int chunkSize,

        /** Overlap between adjacent text chunks in tokens. */
        @Positive int chunkOverlap,

        /** MMR lambda: 1.0 = pure relevance, 0.0 = pure diversity. */
        @DecimalMin("0.0") @DecimalMax("1.0") double rerankMmrLambda,

        /** Blend weight between RRF fusion score (1.0) and dense cosine score (0.0) in MMR. */
        @DecimalMin("0.0") @DecimalMax("1.0") double rerankFusionWeight,

        /** Enable the optional LLM re-rank pass after MMR.  Falls back to MMR order on failure. */
        boolean rerankLlmEnabled,

        /** Cosine similarity below this triggers a low-confidence caveat in the prompt. */
        @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarityThreshold
) {}
