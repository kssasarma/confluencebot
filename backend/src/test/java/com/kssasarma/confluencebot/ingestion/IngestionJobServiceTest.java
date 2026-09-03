package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
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
