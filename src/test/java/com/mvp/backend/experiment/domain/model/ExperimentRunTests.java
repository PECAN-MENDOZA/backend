package com.mvp.backend.experiment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mvp.backend.research.domain.model.ProtocolTask;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;

class ExperimentRunTests {

    @Test
    void completedRunCannotBeCompletedTwice() {
        var run = activeRun(ExperimentCondition.ASSISTED);
        UUID key = UUID.randomUUID();
        run.complete("Texto final", 60_000, key, Instant.now());

        assertThatThrownBy(() -> run.complete("Otro texto", 10_000, UUID.randomUUID(), Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameCompletionKeyIsIdempotent() {
        var run = activeRun(ExperimentCondition.ASSISTED);
        UUID key = UUID.randomUUID();
        run.complete("Texto final", 60_000, key, Instant.now());

        run.complete("Texto final", 60_000, key, Instant.now());

        assertThat(run.getFinalText()).isEqualTo("Texto final");
    }

    @Test
    void redeemedRunKeepsItsCodeUntilStart() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        var run = new ExperimentRun(
                mock(StudyParticipant.class), mock(StudyProtocol.class), mock(ProtocolTask.class),
                ExperimentCondition.ASSISTED, "a".repeat(64), now.plusSeconds(1800), now);

        run.redeem(now.plusSeconds(1));
        run.redeem(now.plusSeconds(5)); // segundo canje (app cerrada y reabierta): idempotente

        assertThat(run.isRedeemedPending()).isTrue();
        assertThat(run.getAccessCodeHash()).isEqualTo("a".repeat(64));
        assertThat(run.getRedeemedAt()).isEqualTo(now.plusSeconds(1));

        run.start(now.plusSeconds(6));
        run.start(now.plusSeconds(7)); // idempotente

        assertThat(run.getAccessCodeHash()).isNull();
        assertThat(run.getStartedAt()).isEqualTo(now.plusSeconds(6));
    }

    @Test
    void incidentDoesNotAlterCompletion() {
        var run = activeRun(ExperimentCondition.ASSISTED);
        run.complete("Texto final", 60_000, UUID.randomUUID(), Instant.now());

        run.recordIncident("DURATION_INCONSISTENT");

        assertThat(run.getIncidentCount()).isEqualTo(1);
        assertThat(run.getFailureReason()).isEqualTo("DURATION_INCONSISTENT");
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
        assertThat(run.getDurationMs()).isEqualTo(60_000);
    }

    private ExperimentRun activeRun(ExperimentCondition condition) {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        var run = new ExperimentRun(
                mock(StudyParticipant.class), mock(StudyProtocol.class), mock(ProtocolTask.class),
                condition, "a".repeat(64), now.plusSeconds(1800), now);
        run.redeem(now.plusSeconds(1));
        run.start(now.plusSeconds(2));
        return run;
    }
}
