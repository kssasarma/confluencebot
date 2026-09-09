package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.exception.GlobalExceptionHandler;
import com.kssasarma.confluencebot.ingestion.IngestionJobService;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class IngestionControllerTest {

    @Mock private IngestionJobService jobService;
    @Mock private ConfluencePageRepository pageRepository;

    private final ConfluenceProperties props = new ConfluenceProperties(
            "http://confluence.example.com", "test-pat", "ENG", 100, 30);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IngestionController(jobService, props, pageRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void ingestSpace_bodyWithSpaceKey_returns202WithPendingJob() throws Exception {
        IngestionJobEntity job = IngestionJobEntity.forSpace("MYSPACE", false);
        when(jobService.submitSpaceJob("MYSPACE", false)).thenReturn(job);

        mockMvc.perform(post("/api/ingest/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceKey": "MYSPACE"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobType").value("SPACE"))
                .andExpect(jsonPath("$.spaceKey").value("MYSPACE"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void ingestSpace_noBody_fallsBackToConfiguredSpaceKey() throws Exception {
        IngestionJobEntity job = IngestionJobEntity.forSpace("ENG", false);
        when(jobService.submitSpaceJob("ENG", false)).thenReturn(job);

        mockMvc.perform(post("/api/ingest/space"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.spaceKey").value("ENG"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void ingestPage_validPageId_returns202WithPendingJob() throws Exception {
        IngestionJobEntity job = IngestionJobEntity.forPage("131073");
        when(jobService.submitPageJob("131073")).thenReturn(job);

        mockMvc.perform(post("/api/ingest/page/131073"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobType").value("PAGE"))
                .andExpect(jsonPath("$.pageId").value("131073"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getJob_existingJobId_returns200WithJob() throws Exception {
        IngestionJobEntity job = IngestionJobEntity.forSpace("ENG", false);
        UUID jobId = UUID.randomUUID();
        when(jobService.findById(jobId)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/api/ingest/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getJob_unknownJobId_returns404() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.findById(jobId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/ingest/jobs/{jobId}", jobId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listJobs_defaultPaging_returnsFirstPageOfFiveAsJobsArray() throws Exception {
        Page<IngestionJobEntity> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 5), 0);
        when(jobService.findAll(any(Pageable.class))).thenReturn(emptyPage);

        mockMvc.perform(get("/api/ingest/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void listJobs_pageAndSizeParams_passedThroughAndCappedAt100() throws Exception {
        Page<IngestionJobEntity> emptyPage = new PageImpl<>(List.of(), PageRequest.of(2, 100), 0);
        when(jobService.findAll(any(Pageable.class))).thenReturn(emptyPage);

        mockMvc.perform(get("/api/ingest/jobs").param("page", "2").param("size", "500"))
                .andExpect(status().isOk());

        verify(jobService).findAll(PageRequest.of(2, 100));
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
