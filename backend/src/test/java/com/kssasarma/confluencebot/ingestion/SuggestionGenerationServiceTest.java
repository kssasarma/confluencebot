package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.chat.LlmGateway;
import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import com.kssasarma.confluencebot.domain.SpaceSuggestion;
import com.kssasarma.confluencebot.exception.LlmUnavailableException;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import com.kssasarma.confluencebot.repository.SpaceSuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuggestionGenerationServiceTest {

    @Mock private LlmGateway llmGateway;
    @Mock private ConfluencePageRepository pageRepository;
    @Mock private SpaceSuggestionRepository suggestionRepository;

    private SuggestionGenerationService service;

    @BeforeEach
    void setUp() {
        service = new SuggestionGenerationService(llmGateway, pageRepository, suggestionRepository);
    }

    private static ConfluencePageEntity page(String title) {
        return ConfluencePageEntity.newPage("p-" + title.hashCode(), "IT", "IT Support", title, "http://x");
    }

    @Test
    void regenerate_modelReturnsCleanLines_replacesTheSpacesSuggestions() {
        when(pageRepository.findBySpaceKey("IT")).thenReturn(List.of(page("VPN Setup"), page("Password Reset")));
        when(llmGateway.complete(any(LlmPrompt.class))).thenReturn(
                "How do I set up VPN access?\nHow do I reset my password?\nWhere do I report an outage?\nWho owns on-call?");

        service.regenerate("IT");

        verify(suggestionRepository).deleteBySpaceKey("IT");
        verify(suggestionRepository, times(4)).save(any(SpaceSuggestion.class));
    }

    @Test
    void regenerate_modelReturnsNumberedBulletedLines_stripsTheMarkers() {
        when(pageRepository.findBySpaceKey("IT")).thenReturn(List.of(page("VPN Setup")));
        when(llmGateway.complete(any(LlmPrompt.class))).thenReturn(
                "1. How do I set up VPN access?\n- Where do I report an outage?\n* Who owns on-call?");

        service.regenerate("IT");

        verify(suggestionRepository).save(argThat(s -> s.getQuestion().equals("How do I set up VPN access?")));
        verify(suggestionRepository).save(argThat(s -> s.getQuestion().equals("Where do I report an outage?")));
        verify(suggestionRepository).save(argThat(s -> s.getQuestion().equals("Who owns on-call?")));
    }

    @Test
    void regenerate_capsAtFourAndDropsDuplicates() {
        when(pageRepository.findBySpaceKey("IT")).thenReturn(List.of(page("VPN Setup")));
        when(llmGateway.complete(any(LlmPrompt.class))).thenReturn(
                "Question one?\nQuestion one?\nQuestion two?\nQuestion three?\nQuestion four?\nQuestion five?");

        service.regenerate("IT");

        verify(suggestionRepository, times(4)).save(any(SpaceSuggestion.class));
    }

    @Test
    void regenerate_spaceHasNoIngestedPages_leavesExistingSuggestionsAlone() {
        when(pageRepository.findBySpaceKey("EMPTY")).thenReturn(List.of());

        service.regenerate("EMPTY");

        verifyNoInteractions(llmGateway);
        verify(suggestionRepository, never()).deleteBySpaceKey(any());
    }

    @Test
    void regenerate_llmFails_leavesExistingSuggestionsInPlaceRatherThanClearingThem() {
        when(pageRepository.findBySpaceKey("IT")).thenReturn(List.of(page("VPN Setup")));
        when(llmGateway.complete(any(LlmPrompt.class))).thenThrow(new LlmUnavailableException("down"));

        service.regenerate("IT");

        verify(suggestionRepository, never()).deleteBySpaceKey(eq("IT"));
        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void regenerate_modelReturnsNothingUsable_leavesExistingSuggestionsInPlace() {
        when(pageRepository.findBySpaceKey("IT")).thenReturn(List.of(page("VPN Setup")));
        when(llmGateway.complete(any(LlmPrompt.class))).thenReturn("   \n  \n");

        service.regenerate("IT");

        verify(suggestionRepository, never()).deleteBySpaceKey(any());
    }
}
