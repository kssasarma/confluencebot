package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository.SpaceKeyName;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpaceController(pageRepository)).build();
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

    private static SpaceKeyName spaceRow(String key, String name) {
        return new SpaceKeyName() {
            @Override public String getSpaceKey() { return key; }
            @Override public String getSpaceName() { return name; }
        };
    }
}
