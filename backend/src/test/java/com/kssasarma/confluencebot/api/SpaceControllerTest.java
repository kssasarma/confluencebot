package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.domain.SpaceSuggestion;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository.SpaceKeyName;
import com.kssasarma.confluencebot.repository.SpaceSuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SpaceControllerTest {

    @Mock private ConfluencePageRepository pageRepository;
    @Mock private SpaceSuggestionRepository suggestionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpaceController(pageRepository, suggestionRepository)).build();
    }

    @Test
    void listSpaces_returnsEverySpaceSortedByName() throws Exception {
        when(pageRepository.findDistinctSpaces()).thenReturn(List.of(
                spaceRow("IT", "IT Support"),
                spaceRow("ENG", "Engineering")));

        mockMvc.perform(get("/api/spaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].key").value("ENG"))
                .andExpect(jsonPath("$[0].name").value("Engineering"))
                .andExpect(jsonPath("$[1].key").value("IT"))
                .andExpect(jsonPath("$[1].name").value("IT Support"));
    }

    @Test
    void listSpaces_noSpaceNameRecorded_fallsBackToKey() throws Exception {
        when(pageRepository.findDistinctSpaces()).thenReturn(List.of(spaceRow("OPS", null)));

        mockMvc.perform(get("/api/spaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("OPS"))
                .andExpect(jsonPath("$[0].name").value("OPS"));
    }

    @Test
    void listSpaces_noneIngestedYet_returnsEmptyList() throws Exception {
        when(pageRepository.findDistinctSpaces()).thenReturn(List.of());

        mockMvc.perform(get("/api/spaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void suggestions_withSpaceKey_returnsThatSpacesQuestionsInOrder() throws Exception {
        when(suggestionRepository.findBySpaceKeyOrderByIdAsc("IT")).thenReturn(List.of(
                new SpaceSuggestion("IT", "How do I request VPN access?"),
                new SpaceSuggestion("IT", "Where is the on-call rotation documented?")));

        mockMvc.perform(get("/api/spaces/suggestions").param("spaceKey", "IT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]").value("How do I request VPN access?"))
                .andExpect(jsonPath("$[1]").value("Where is the on-call rotation documented?"));
    }

    @Test
    void suggestions_noSpaceKey_samplesAcrossEverySpace() throws Exception {
        when(suggestionRepository.findRandomSample(4)).thenReturn(List.of(
                new SpaceSuggestion("ENG", "How do I deploy to production?")));

        mockMvc.perform(get("/api/spaces/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0]").value("How do I deploy to production?"));
    }

    @Test
    void suggestions_noneGeneratedYet_returnsEmptyList() throws Exception {
        when(suggestionRepository.findBySpaceKeyOrderByIdAsc("NEW")).thenReturn(List.of());

        mockMvc.perform(get("/api/spaces/suggestions").param("spaceKey", "NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private static SpaceKeyName spaceRow(String key, String name) {
        return new SpaceKeyName() {
            @Override public String getSpaceKey() { return key; }
            @Override public String getSpaceName() { return name; }
        };
    }
}
