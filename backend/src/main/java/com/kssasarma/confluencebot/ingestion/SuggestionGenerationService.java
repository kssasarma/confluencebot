package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.chat.LlmGateway;
import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import com.kssasarma.confluencebot.domain.SpaceSuggestion;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import com.kssasarma.confluencebot.repository.SpaceSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Regenerates the welcome-screen suggestions for a space from what was actually ingested.
 *
 * Replaces the frontend's old hard-coded, generic questions ("What is our onboarding process?")
 * with ones grounded in the space's own page titles — so a newly ingested space is never left
 * showing a prior space's suggestions, or none at all.
 *
 * <p>Best-effort by design: called after a space finishes ingesting, and a model hiccup here must
 * never fail — or retry — the ingestion job it rode in on. A failure leaves the space's previous
 * suggestions in place rather than clearing them, which is friendlier than a blank welcome screen.
 */
@Service
public class SuggestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionGenerationService.class);

    /** How many suggestions the welcome screen shows per space. */
    static final int SUGGESTION_COUNT = 4;

    /** Page titles are sampled rather than exhaustively listed — enough for the model to infer
     * the space's subject matter without the prompt growing with the size of the space. */
    private static final int MAX_TITLES = 40;

    private static final Pattern LEADING_MARKER = Pattern.compile("^[\\s\\-*\\u2022\\d.)]+");

    private final LlmGateway llmGateway;
    private final ConfluencePageRepository pageRepository;
    private final SpaceSuggestionRepository suggestionRepository;

    public SuggestionGenerationService(LlmGateway llmGateway, ConfluencePageRepository pageRepository,
                                       SpaceSuggestionRepository suggestionRepository) {
        this.llmGateway = llmGateway;
        this.pageRepository = pageRepository;
        this.suggestionRepository = suggestionRepository;
    }

    @Transactional
    public void regenerate(String spaceKey) {
        List<ConfluencePageEntity> pages = pageRepository.findBySpaceKey(spaceKey);
        if (pages.isEmpty()) {
            log.info("Space {} has no ingested pages — leaving its suggestions as they are", spaceKey);
            return;
        }

        String spaceName = pages.stream()
                .map(ConfluencePageEntity::getSpaceName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(spaceKey);

        List<String> titles = pages.stream()
                .map(ConfluencePageEntity::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .limit(MAX_TITLES)
                .toList();

        if (titles.isEmpty()) {
            log.info("Space {} has no titled pages — leaving its suggestions as they are", spaceKey);
            return;
        }

        List<String> questions;
        try {
            questions = askForQuestions(spaceKey, spaceName, titles);
        } catch (Exception e) {
            log.warn("Could not generate suggestions for space {}: {}", spaceKey, e.getMessage());
            return;
        }

        if (questions.isEmpty()) {
            log.warn("Model returned no usable suggestions for space {}", spaceKey);
            return;
        }

        suggestionRepository.deleteBySpaceKey(spaceKey);
        for (String question : questions) {
            suggestionRepository.save(new SpaceSuggestion(spaceKey, question));
        }
        log.info("Generated {} suggestion(s) for space {}", questions.size(), spaceKey);
    }

    private List<String> askForQuestions(String spaceKey, String spaceName, List<String> titles) {
        String system = "You write short example questions for a documentation chatbot's welcome "
                + "screen. Base every question only on the page titles given — never invent facts "
                + "about content you have not been shown.";

        String user = "Space: %s (%s)\n\nPage titles:\n- %s\n\nWrite exactly %d short, concrete "
                + "questions a reader of this space might ask the chatbot, one per line, with no "
                + "numbering, bullets, or extra commentary.".formatted(
                        spaceName != null && !spaceName.isBlank() ? spaceName : spaceKey,
                        spaceKey, String.join("\n- ", titles), SUGGESTION_COUNT);

        String raw = llmGateway.complete(new LlmPrompt(system, user));
        return parseQuestions(raw);
    }

    /** Strips numbering/bullets, drops blanks and duplicates, caps at {@link #SUGGESTION_COUNT}. */
    private static List<String> parseQuestions(String raw) {
        if (raw == null) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        List<String> questions = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String cleaned = LEADING_MARKER.matcher(line).replaceFirst("").strip();
            if (cleaned.isEmpty() || !seen.add(cleaned.toLowerCase())) continue;
            questions.add(cleaned);
            if (questions.size() == SUGGESTION_COUNT) break;
        }
        return questions;
    }
}
