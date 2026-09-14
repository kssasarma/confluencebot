package com.kssasarma.confluencebot.schedule;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionScheduleEntity;
import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.ingestion.IngestionJobService;
import com.kssasarma.confluencebot.repository.IngestionScheduleRepository;
import com.kssasarma.confluencebot.schedule.command.CreateScheduleCommand;
import com.kssasarma.confluencebot.schedule.command.UpdateScheduleCommand;
import com.kssasarma.confluencebot.schedule.strategy.FixedIntervalScheduleStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionScheduleServiceTest {

    @Mock private IngestionScheduleRepository scheduleRepo;
    @Mock private IngestionJobService jobService;

    private IngestionScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IngestionScheduleServiceImpl(
                scheduleRepo, new FixedIntervalScheduleStrategy(), jobService);
    }

    // ─── createOrReplace ─────────────────────────────────────────────────────

    @Test
    void createOrReplace_newSpace_createsEntity() {
        when(scheduleRepo.findBySpaceKey("IT")).thenReturn(Optional.empty());
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateScheduleCommand cmd = new CreateScheduleCommand(24, true, false, "admin@example.com");
        IngestionScheduleEntity result = service.createOrReplace("IT", cmd);

        assertThat(result.getSpaceKey()).isEqualTo("IT");
        assertThat(result.getIntervalHours()).isEqualTo(24);
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.isForce()).isFalse();
        assertThat(result.getCreatedBy()).isEqualTo("admin@example.com");
        assertThat(result.getNextRunAt()).isAfter(OffsetDateTime.now().minusSeconds(5));
        verify(scheduleRepo).save(result);
    }

    @Test
    void createOrReplace_existingSpace_updatesInPlace() {
        IngestionScheduleEntity existing = IngestionScheduleEntity.create(
                "IT", 24, true, false, "orig@example.com", OffsetDateTime.now().plusHours(24));
        when(scheduleRepo.findBySpaceKey("IT")).thenReturn(Optional.of(existing));
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateScheduleCommand cmd = new CreateScheduleCommand(12, false, true, "admin2@example.com");
        IngestionScheduleEntity result = service.createOrReplace("IT", cmd);

        assertThat(result.getIntervalHours()).isEqualTo(12);
        assertThat(result.isEnabled()).isFalse();
        assertThat(result.isForce()).isTrue();
        assertThat(result.getUpdatedBy()).isEqualTo("admin2@example.com");
        verify(scheduleRepo).save(existing);
    }

    // ─── update ──────────────────────────────────────────────────────────────

    @Test
    void update_existingSchedule_appliesPartialChange() {
        IngestionScheduleEntity existing = IngestionScheduleEntity.create(
                "IT", 24, true, false, "admin@example.com", OffsetDateTime.now().plusHours(24));
        when(scheduleRepo.findBySpaceKey("IT")).thenReturn(Optional.of(existing));
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleCommand cmd = new UpdateScheduleCommand(null, false, null, "admin@example.com");
        IngestionScheduleEntity result = service.update("IT", cmd);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getIntervalHours()).isEqualTo(24);  // unchanged
        assertThat(result.isForce()).isFalse();               // unchanged
    }

    @Test
    void update_unknownSpace_throwsResourceNotFound() {
        when(scheduleRepo.findBySpaceKey("MISSING")).thenReturn(Optional.empty());

        UpdateScheduleCommand cmd = new UpdateScheduleCommand(null, false, null, "admin@example.com");
        assertThatThrownBy(() -> service.update("MISSING", cmd))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("MISSING");
    }

    // ─── delete ──────────────────────────────────────────────────────────────

    @Test
    void delete_existingSchedule_removesIt() {
        IngestionScheduleEntity existing = IngestionScheduleEntity.create(
                "IT", 24, true, false, "admin@example.com", OffsetDateTime.now().plusHours(24));
        when(scheduleRepo.findBySpaceKey("IT")).thenReturn(Optional.of(existing));

        service.delete("IT");

        verify(scheduleRepo).delete(existing);
    }

    @Test
    void delete_unknownSpace_throwsResourceNotFound() {
        when(scheduleRepo.findBySpaceKey("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("MISSING"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── triggerNow ──────────────────────────────────────────────────────────

    @Test
    void triggerNow_existingSchedule_submitsJobWithScheduledForceFlag() {
        IngestionScheduleEntity existing = IngestionScheduleEntity.create(
                "IT", 24, true, true, "admin@example.com", OffsetDateTime.now().plusHours(24));
        when(scheduleRepo.findBySpaceKey("IT")).thenReturn(Optional.of(existing));
        IngestionJobEntity fakeJob = IngestionJobEntity.forSpace("IT", true, "admin@example.com");
        when(jobService.submitSpaceJob("IT", true, "admin@example.com")).thenReturn(fakeJob);

        IngestionJobEntity result = service.triggerNow("IT", "admin@example.com");

        assertThat(result).isSameAs(fakeJob);
        verify(jobService).submitSpaceJob("IT", true, "admin@example.com");
    }

    @Test
    void triggerNow_unknownSpace_throwsResourceNotFound() {
        when(scheduleRepo.findBySpaceKey("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triggerNow("MISSING", "admin@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── claimDueSchedules ───────────────────────────────────────────────────

    @Test
    void claimDueSchedules_noDueSchedules_returnsEmpty() {
        when(scheduleRepo.findByEnabledTrueAndNextRunAtLessThanEqual(any())).thenReturn(List.of());

        List<ScheduledRunSpec> specs = service.claimDueSchedules(OffsetDateTime.now());

        assertThat(specs).isEmpty();
        verify(scheduleRepo, never()).save(any());
    }

    @Test
    void claimDueSchedules_dueSchedules_advancesNextRunAtAndReturnsSpecs() {
        OffsetDateTime now = OffsetDateTime.now();
        IngestionScheduleEntity s1 = IngestionScheduleEntity.create(
                "IT", 24, true, false, "admin@example.com", now.minusMinutes(1));
        IngestionScheduleEntity s2 = IngestionScheduleEntity.create(
                "HR", 12, true, true, "admin@example.com", now.minusMinutes(5));

        when(scheduleRepo.findByEnabledTrueAndNextRunAtLessThanEqual(any()))
                .thenReturn(List.of(s1, s2));
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ScheduledRunSpec> specs = service.claimDueSchedules(now);

        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(ScheduledRunSpec::spaceKey).containsExactlyInAnyOrder("IT", "HR");

        // Both schedules must have been advanced past now
        assertThat(s1.getNextRunAt()).isAfter(now);
        assertThat(s1.getLastRunAt()).isEqualTo(now);
        assertThat(s2.getNextRunAt()).isAfter(now);
        assertThat(s2.getLastRunAt()).isEqualTo(now);

        // s1 (24h interval) fires later than s2 (12h interval)
        assertThat(s1.getNextRunAt()).isAfter(s2.getNextRunAt());

        verify(scheduleRepo, times(2)).save(any());
    }

    @Test
    void claimDueSchedules_returnsCorrectForceFlag() {
        OffsetDateTime now = OffsetDateTime.now();
        IngestionScheduleEntity forced = IngestionScheduleEntity.create(
                "ENG", 24, true, true, "admin@example.com", now.minusMinutes(1));
        when(scheduleRepo.findByEnabledTrueAndNextRunAtLessThanEqual(any()))
                .thenReturn(List.of(forced));
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<ScheduledRunSpec> specs = service.claimDueSchedules(now);

        assertThat(specs.getFirst().force()).isTrue();
    }
}
