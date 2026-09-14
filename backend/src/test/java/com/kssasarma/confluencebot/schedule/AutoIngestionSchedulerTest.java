package com.kssasarma.confluencebot.schedule;

import com.kssasarma.confluencebot.exception.DuplicateIngestionJobException;
import com.kssasarma.confluencebot.ingestion.IngestionJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoIngestionSchedulerTest {

    @Mock private IngestionScheduleService scheduleService;
    @Mock private IngestionJobService jobService;

    @InjectMocks private AutoIngestionScheduler scheduler;

    @Test
    void checkAndTrigger_noDueSchedules_submitsNothing() {
        when(scheduleService.claimDueSchedules(any())).thenReturn(List.of());

        scheduler.checkAndTriggerDueSchedules();

        verifyNoInteractions(jobService);
    }

    @Test
    void checkAndTrigger_oneScheduleDue_submitsOneJob() {
        when(scheduleService.claimDueSchedules(any()))
                .thenReturn(List.of(new ScheduledRunSpec("IT", false)));

        scheduler.checkAndTriggerDueSchedules();

        verify(jobService).submitSpaceJob("IT", false);
    }

    @Test
    void checkAndTrigger_multipleSchedulesDue_submitsAllJobs() {
        when(scheduleService.claimDueSchedules(any()))
                .thenReturn(List.of(
                        new ScheduledRunSpec("IT", false),
                        new ScheduledRunSpec("HR", true)));

        scheduler.checkAndTriggerDueSchedules();

        verify(jobService).submitSpaceJob("IT", false);
        verify(jobService).submitSpaceJob("HR", true);
    }

    @Test
    void checkAndTrigger_duplicateJobException_logsSkipAndContinues() {
        when(scheduleService.claimDueSchedules(any()))
                .thenReturn(List.of(
                        new ScheduledRunSpec("IT", false),
                        new ScheduledRunSpec("HR", false)));
        doThrow(new DuplicateIngestionJobException("already running"))
                .when(jobService).submitSpaceJob("IT", false);

        // Must not throw; HR job must still be submitted after IT fails
        scheduler.checkAndTriggerDueSchedules();

        verify(jobService).submitSpaceJob("IT", false);
        verify(jobService).submitSpaceJob("HR", false);
    }

    @Test
    void checkAndTrigger_unexpectedException_logsErrorAndContinues() {
        when(scheduleService.claimDueSchedules(any()))
                .thenReturn(List.of(
                        new ScheduledRunSpec("IT", false),
                        new ScheduledRunSpec("HR", false)));
        doThrow(new RuntimeException("db down"))
                .when(jobService).submitSpaceJob("IT", false);

        scheduler.checkAndTriggerDueSchedules();

        verify(jobService).submitSpaceJob("HR", false);
    }

    @Test
    void checkAndTrigger_passesNowToClaimSchedules() {
        when(scheduleService.claimDueSchedules(any())).thenReturn(List.of());
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        scheduler.checkAndTriggerDueSchedules();

        verify(scheduleService).claimDueSchedules(argThat(
                t -> t.isAfter(before) && t.isBefore(OffsetDateTime.now().plusSeconds(1))));
    }
}
