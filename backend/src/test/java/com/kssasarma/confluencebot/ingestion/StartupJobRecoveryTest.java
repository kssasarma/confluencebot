package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionJobStatus;
import com.kssasarma.confluencebot.repository.IngestionJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartupJobRecoveryTest {

    @Mock private IngestionJobRepository jobRepo;
    @InjectMocks private StartupJobRecovery recovery;

    @Test
    void failStaleJobs_pendingAndRunningJobsAreFailedWithExplanatoryMessage() {
        IngestionJobEntity pending = IngestionJobEntity.forSpace("HR", false, "system");
        IngestionJobEntity running = IngestionJobEntity.forSpace("IT", true, "system");
        running.markRunning();
        when(jobRepo.findByStatusIn(anyCollection())).thenReturn(List.of(pending, running));

        recovery.failStaleJobs();

        assertThat(pending.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(pending.getErrorMessage()).contains("PENDING");
        assertThat(running.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(running.getErrorMessage()).contains("RUNNING");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<IngestionJobEntity>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(jobRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(pending, running);
    }

    @Test
    void failStaleJobs_noStaleJobs_doesNotSave() {
        when(jobRepo.findByStatusIn(anyCollection())).thenReturn(List.of());

        recovery.failStaleJobs();

        verify(jobRepo, never()).saveAll(anyCollection());
    }

    @Test
    void failStaleJobs_queriesOnlyPendingAndRunningStatuses() {
        when(jobRepo.findByStatusIn(anyCollection())).thenReturn(List.of());

        recovery.failStaleJobs();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<IngestionJobStatus>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(jobRepo).findByStatusIn(captor.capture());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(IngestionJobStatus.PENDING, IngestionJobStatus.RUNNING);
    }
}
