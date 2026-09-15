package com.mvp.backend.experiment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mvp.backend.research.domain.model.ProtocolTask;
import com.mvp.backend.research.domain.model.Researcher;
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

        Instant at = Instant.parse("2026-09-14T12:10:00Z");
        run.recordIncident("DURATION_INCONSISTENT", at);
        run.recordIncident("MODEL_VERSION_CHANGED", at.plusSeconds(5));

        assertThat(run.getIncidentCount()).isEqualTo(2);
        assertThat(run.getFailureReason()).isEqualTo("MODEL_VERSION_CHANGED");
        // Historial append-only: ambos motivos quedan, en orden y con su instante.
        assertThat(run.getIncidents()).extracting(ExperimentIncident::getReason)
                .containsExactly("DURATION_INCONSISTENT", "MODEL_VERSION_CHANGED");
        assertThat(run.getIncidents()).extracting(ExperimentIncident::getCreatedAt)
                .containsExactly(at, at.plusSeconds(5));
        assertThat(run.getIncidents()).allSatisfy(incident -> assertThat(incident.getRun()).isSameAs(run));
        assertThatThrownBy(() -> run.getIncidents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
        assertThat(run.getDurationMs()).isEqualTo(60_000);
    }

    @Test
    void redeemedRunThatOutlivesItsCodeExpiresOnStart() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        var run = pendingRun(now); // vence a los 1800 s
        run.redeem(now.plusSeconds(60));

        assertThatThrownBy(() -> run.start(now.plusSeconds(1801)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Access code expired before start");

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.EXPIRED);
        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getAccessCodeHash()).isNull();
        assertThat(run.getCompletedAt()).isEqualTo(now.plusSeconds(1801));
    }

    @Test
    void cancelFromPendingClearsCodeAndIsIdempotent() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        var run = pendingRun(now);

        run.cancel("El alumno decidio no continuar", now.plusSeconds(10));
        run.cancel("Otra razon distinta posterior", now.plusSeconds(20)); // idempotente

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.CANCELLED);
        assertThat(run.getAccessCodeHash()).isNull();
        assertThat(run.getFailureReason()).isEqualTo("El alumno decidio no continuar");
        assertThat(run.getCompletedAt()).isEqualTo(now.plusSeconds(10));
    }

    @Test
    void cancelRejectsCompletedRun() {
        var run = activeRun(ExperimentCondition.ASSISTED);
        run.complete("Texto final", 60_000, UUID.randomUUID(), Instant.now());

        assertThatThrownBy(() -> run.cancel("El alumno decidio no continuar", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
    }

    @Test
    void expireOnlyFromPending() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        var pending = pendingRun(now);
        pending.expire(now.plusSeconds(1800));
        pending.expire(now.plusSeconds(1900)); // idempotente

        assertThat(pending.getStatus()).isEqualTo(ExperimentRunStatus.EXPIRED);
        assertThat(pending.getAccessCodeHash()).isNull();
        assertThat(pending.getCompletedAt()).isEqualTo(now.plusSeconds(1800));

        var active = activeRun(ExperimentCondition.ASSISTED);
        assertThatThrownBy(() -> active.expire(Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(active.getStatus()).isEqualTo(ExperimentRunStatus.ACTIVE);
    }

    @Test
    void technicalFailureCountsAsIncident() {
        var run = activeRun(ExperimentCondition.ASSISTED);
        Instant now = Instant.parse("2026-09-14T12:30:00Z");

        run.failTechnically("La sesion de correccion no respondio", now);
        run.failTechnically("Segunda llamada ignorada por idempotencia", now.plusSeconds(1));

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.TECHNICAL_FAILURE);
        assertThat(run.getIncidentCount()).isEqualTo(1);
        assertThat(run.getIncidents()).extracting(ExperimentIncident::getReason).containsExactly("TECHNICAL_FAILURE");
        assertThat(run.getFailureReason()).isEqualTo("La sesion de correccion no respondio");
        assertThat(run.getCompletedAt()).isEqualTo(now);
        assertThat(run.getAccessCodeHash()).isNull();
    }

    @Test
    void excludeRequiresCompletedOrFailedAndReason() {
        Instant now = Instant.parse("2026-09-14T13:00:00Z");
        var researcher = mock(Researcher.class);

        var active = activeRun(ExperimentCondition.ASSISTED);
        assertThatThrownBy(() -> active.exclude("Texto pegado desde otra app", researcher, now))
                .isInstanceOf(IllegalStateException.class);

        var completed = activeRun(ExperimentCondition.ASSISTED);
        completed.complete("Texto final", 60_000, UUID.randomUUID(), now);
        assertThatThrownBy(() -> completed.exclude("abc", researcher, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(completed.isExcluded()).isFalse();

        completed.exclude("Texto pegado desde otra app", researcher, now);
        assertThat(completed.isExcluded()).isTrue();
        assertThat(completed.getExclusionReason()).isEqualTo("Texto pegado desde otra app");
        assertThat(completed.getExcludedBy()).isSameAs(researcher);
        assertThat(completed.getExcludedAt()).isEqualTo(now);
        assertThat(completed.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);

        assertThatThrownBy(() -> completed.exclude("Se excluye por segunda vez", researcher, now))
                .isInstanceOf(IllegalStateException.class);

        var failed = activeRun(ExperimentCondition.UNASSISTED);
        failed.failTechnically("La sesion de correccion no respondio", now);
        failed.exclude("Falla tecnica documentada por el equipo", researcher, now);
        assertThat(failed.isExcluded()).isTrue();
    }

    @Test
    void modelVersionIsWriteOnce() {
        var run = activeRun(ExperimentCondition.ASSISTED);

        assertThat(run.recordModelVersion("m1")).isTrue();
        assertThat(run.recordModelVersion("m1")).isTrue();
        assertThat(run.recordModelVersion("m2")).isFalse();
        assertThat(run.getModelVersion()).isEqualTo("m1");
    }

    @Test
    void backendVersionOnlyWhileActive() {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        var pending = pendingRun(now);
        pending.recordBackendVersion("b1");
        assertThat(pending.getBackendVersion()).isNull();

        var active = activeRun(ExperimentCondition.ASSISTED);
        active.recordBackendVersion("b1");
        active.recordBackendVersion("b2"); // write-once
        assertThat(active.getBackendVersion()).isEqualTo("b1");

        active.recordAppVersion("a1"); // solo al completar
        assertThat(active.getAppVersion()).isNull();
        active.complete("Texto final", 60_000, UUID.randomUUID(), now.plusSeconds(60));
        active.recordAppVersion("a1");
        active.recordAppVersion("a2");
        assertThat(active.getAppVersion()).isEqualTo("a1");
    }

    private ExperimentRun pendingRun(Instant now) {
        return new ExperimentRun(
                mock(StudyParticipant.class), mock(StudyProtocol.class), mock(ProtocolTask.class),
                ExperimentCondition.ASSISTED, "a".repeat(64), now.plusSeconds(1800), now);
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
