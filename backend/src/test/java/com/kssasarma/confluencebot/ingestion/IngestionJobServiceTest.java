package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionJobStatus;
import com.kssasarma.confluencebot.repository.IngestionJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The submit methods run inside a transaction, so the job row does not exist for any other
 * connection until that transaction commits. These tests pin the ordering that keeps the
 * background runner from reading a row that is not there yet.
 */
@ExtendWith(MockitoExtension.class)
class IngestionJobServiceTest {

    @Mock private IngestionJobRepository jobRepo;
    @Mock private IngestionJobRunner runner;

    @InjectMocks private IngestionJobService service;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void submitPageJob_insideTransaction_doesNotDispatchUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        UUID jobId = stubIdOnSave();

        IngestionJobEntity job = service.submitPageJob("131073");

        assertThat(job.getId()).isEqualTo(jobId);
        verify(jobRepo).save(job);
        verifyNoInteractions(runner);

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(runner).runPageJob(jobId, "131073");
    }

    @Test
    void submitSpaceJob_insideTransaction_doesNotDispatchUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        UUID jobId = stubIdOnSave();

        service.submitSpaceJob("IT", true);

        verifyNoInteractions(runner);

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(runner).runSpaceJob(jobId, "IT", true);
    }

    @Test
    void submitPageJob_rolledBackTransaction_neverDispatches() {
        TransactionSynchronizationManager.initSynchronization();
        stubIdOnSave();

        service.submitPageJob("131073");

        // A rollback runs the completion callbacks but not afterCommit. There is no row left to
        // read, so a job handed to the pool here could only fail on arrival.
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(runner, never()).runPageJob(any(), any());
    }

    @Test
    void submitPageJob_withoutTransaction_dispatchesImmediately() {
        UUID jobId = stubIdOnSave();

        service.submitPageJob("131073");

        verify(runner).runPageJob(jobId, "131073");
    }

    @Test
    void retriggerJob_failedSpaceJob_runsAFreshJobAndLeavesTheFailureIntact() {
        IngestionJobEntity failed = IngestionJobEntity.forSpace("IT", true);
        failed.markFailed("Confluence timed out");
        UUID failedId = UUID.randomUUID();
        ReflectionTestUtils.setField(failed, "id", failedId);
        when(jobRepo.findById(failedId)).thenReturn(Optional.of(failed));
        UUID retryId = stubIdOnSave();

        Optional<IngestionJobEntity> retry = service.retriggerJob(failedId);

        assertThat(retry).isPresent();
        assertThat(retry.get().getStatus()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(retry.get().getSpaceKey()).isEqualTo("IT");
        assertThat(retry.get().isForce()).isTrue();
        verify(runner).runSpaceJob(retryId, "IT", true);

        // The failure is the only record of what went wrong; retrying must not overwrite it.
        assertThat(failed.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("Confluence timed out");
    }

    @Test
    void retriggerJob_failedPageJob_runsAFreshPageJob() {
        IngestionJobEntity failed = IngestionJobEntity.forPage("131073");
        failed.markFailed("boom");
        UUID failedId = UUID.randomUUID();
        ReflectionTestUtils.setField(failed, "id", failedId);
        when(jobRepo.findById(failedId)).thenReturn(Optional.of(failed));
        UUID retryId = stubIdOnSave();

        assertThat(service.retriggerJob(failedId)).isPresent();

        verify(runner).runPageJob(retryId, "131073");
    }

    @Test
    void retriggerJob_jobThatHasNotFailed_isRefused() {
        // Retriggering a running job would put two ingestions over the same space at once, and a
        // completed one has nothing to retry.
        IngestionJobEntity completed = IngestionJobEntity.forSpace("IT", false);
        completed.markCompleted(3, 9, 0);
        UUID completedId = UUID.randomUUID();
        ReflectionTestUtils.setField(completed, "id", completedId);
        when(jobRepo.findById(completedId)).thenReturn(Optional.of(completed));

        assertThat(service.retriggerJob(completedId)).isEmpty();

        verify(jobRepo, never()).save(any(IngestionJobEntity.class));
        verifyNoInteractions(runner);
    }

    @Test
    void retriggerJob_unknownJob_isEmpty() {
        UUID missing = UUID.randomUUID();
        when(jobRepo.findById(missing)).thenReturn(Optional.empty());

        assertThat(service.retriggerJob(missing)).isEmpty();

        verifyNoInteractions(runner);
    }

    /**
     * Stands in for Hibernate's UUID generator, which stamps the id during save() without
     * touching the database — the id reaches the caller long before the row reaches the database.
     */
    private UUID stubIdOnSave() {
        UUID jobId = UUID.randomUUID();
        when(jobRepo.save(any(IngestionJobEntity.class))).thenAnswer(invocation -> {
            IngestionJobEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", jobId);
            return saved;
        });
        return jobId;
    }
}
