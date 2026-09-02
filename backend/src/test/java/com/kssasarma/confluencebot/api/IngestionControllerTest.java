package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.exception.GlobalExceptionHandler;
import com.kssasarma.confluencebot.ingestion.IngestionResult;
import com.kssasarma.confluencebot.ingestion.IngestionService;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class IngestionControllerTest {

    @Mock private IngestionService ingestionService;
    @Mock private ConfluencePageRepository pageRepository;

    // Direct instantiation — avoids mocking a record and matches production behaviour
    private final ConfluenceProperties props = new ConfluenceProperties(
            "http://confluence.example.com", "test-pat", "ENG", 100, 30);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IngestionController(ingestionService, props, pageRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void ingestSpace_bodyWithSpaceKey_usesProvidedKey() throws Exception {
        when(ingestionService.ingestSpace("MYSPACE", false))
                .thenReturn(new IngestionResult(10, 50, 2, 1000L));

        mockMvc.perform(post("/api/ingest/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceKey": "MYSPACE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.pagesProcessed").value(10))
                .andExpect(jsonPath("$.chunksStored").value(50))
                .andExpect(jsonPath("$.pagesSkipped").value(2));
    }

    @Test
    void ingestSpace_noBody_fallsBackToConfiguredSpaceKey() throws Exception {
        // props.spaceKey() returns "ENG" from the directly constructed ConfluenceProperties
        when(ingestionService.ingestSpace("ENG", false))
                .thenReturn(new IngestionResult(5, 20, 0, 500L));

        mockMvc.perform(post("/api/ingest/space"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagesProcessed").value(5));
    }

    @Test
    void ingestPage_validPageId_returns200WithSuccess() throws Exception {
        when(ingestionService.ingestPage("131073"))
                .thenReturn(new IngestionResult(1, 7, 0, 300L));

        mockMvc.perform(post("/api/ingest/page/131073"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.chunksStored").value(7));
    }

    @Test
    void listPages_noFilter_returnsAllPagesAsArray() throws Exception {
        when(pageRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/ingest/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listPages_withSpaceKeyParam_filtersResultsBySpace() throws Exception {
        when(pageRepository.findBySpaceKey("ENG")).thenReturn(List.of());

        mockMvc.perform(get("/api/ingest/pages").param("spaceKey", "ENG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
