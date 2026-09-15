package com.mvp.backend.experiment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.mvp.backend.experiment.application.dto.CancelExperimentRequest;
import com.mvp.backend.experiment.application.dto.CompleteExperimentRequest;
import com.mvp.backend.experiment.application.dto.ExperimentRunResponse;
import com.mvp.backend.experiment.application.dto.RedeemAccessCodeRequest;
import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.TaskVariant;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

@ExtendWith(MockitoExtension.class)
class StudentExperimentServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String CODE = "ABCD2345";
    private static final String INVALID = "Access code is invalid or unavailable";
    private static final String TOO_MANY = "Too many failed redemption attempts, try again later";

    @Mock
    private ExperimentRunRepository runRepository;

    @Mock
    private StudyParticipantRepository participantRepository;

    @Mock
    private StudentRepository studentRepository;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private StudentExperimentService service;

    private ResearchStudy study;
    private StudyProtocol protocol;
    private Student student;
    private Student otherStudent;
    private UUID firstStudentId;
    private UUID secondStudentId;

    @BeforeEach
    void setUp() {
        service = new StudentExperimentService(runRepository, participantRepository, studentRepository, clock, "test-build");
        study = new ResearchStudy("EXP-01", "Teclado predictivo", new Researcher("lab@example.edu", "hash"));
        study.activate();
        protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Cuenta tu fin de semana");
        protocol.addTask(TaskVariant.TASK_B, "Describe tu escuela");
        protocol.activate();
        student = new Student("alumno1", "Colegio", "hash");
        otherStudent = new Student("alumno2", "Colegio", "hash");
        firstStudentId = student.getId();
        secondStudentId = otherStudent.getId();
        // Every redemption resolves the authenticated student before touching the code.
        lenient().when(studentRepository.findById(firstStudentId)).thenReturn(Optional.of(student));
        lenient().when(studentRepository.findById(secondStudentId)).thenReturn(Optional.of(otherStudent));
    }

    // ------------------------------------------------------------------ redeem

    @Test
    void firstRedemptionBindsParticipantAndSecondStudentIsRejected() {
        var run = pendingRun();
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.of(run));
        when(studentRepository.findById(firstStudentId)).thenReturn(Optional.of(student));
        when(participantRepository.findByStudyIdAndStudentId(study.getId(), firstStudentId)).thenReturn(Optional.empty());

        var redeemed = service.redeem(firstStudentId, new RedeemAccessCodeRequest(CODE));
        assertThat(redeemed.condition()).isEqualTo(ExperimentCondition.ASSISTED);
        assertThat(redeemed.status()).isEqualTo("PENDING");
        assertThat(redeemed.participantCode()).isEqualTo("P-001");
        assertThat(redeemed.taskVariant()).isEqualTo(TaskVariant.TASK_A);
        assertThat(redeemed.promptText()).isEqualTo("Cuenta tu fin de semana");
        assertThat(redeemed.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(run.getParticipant().isLinkedTo(firstStudentId)).isTrue();
        assertThat(run.getRedeemedAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> service.redeem(secondStudentId, new RedeemAccessCodeRequest(CODE)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(INVALID);
        assertThat(run.getParticipant().isLinkedTo(firstStudentId)).isTrue();
    }

    @Test
    void sameStudentCanRedeemAgainBeforeStarting() {
        var run = pendingRun();
        run.getParticipant().linkStudent(student);
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.of(run));

        var first = service.redeem(firstStudentId, new RedeemAccessCodeRequest(" abcd2345 "));
        var second = service.redeem(firstStudentId, new RedeemAccessCodeRequest(CODE));

        assertThat(second.id()).isEqualTo(first.id()).isEqualTo(run.getId());
        assertThat(run.getRedeemedAt()).isEqualTo(NOW);
        assertThat(run.getAccessCodeHash()).isEqualTo(AccessCode.hash(CODE));
        verify(participantRepository, never()).findByStudyIdAndStudentId(any(), any());
    }

    @Test
    void unknownCodeIsRejectedWithTheGenericMessage() {
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest(CODE)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(INVALID);
    }

    @Test
    void expiredCodeIsMarkedExpiredAndRejected() {
        var run = new ExperimentRun(new StudyParticipant(study, 1), protocol,
                protocol.findTask(TaskVariant.TASK_A).orElseThrow(), ExperimentCondition.ASSISTED,
                AccessCode.hash(CODE), NOW.minusSeconds(1), NOW.minus(TTL));
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest(CODE)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(INVALID);

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.EXPIRED);
        assertThat(run.getAccessCodeHash()).isNull();
        verify(runRepository).save(run);
        verify(participantRepository, never()).findByStudyIdAndStudentId(any(), any());
    }

    @Test
    void studentAlreadyBoundToAnotherParticipantOfTheStudyIsRejected() {
        var run = pendingRun();
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.of(run));
        when(studentRepository.findById(firstStudentId)).thenReturn(Optional.of(student));
        when(participantRepository.findByStudyIdAndStudentId(study.getId(), firstStudentId))
                .thenReturn(Optional.of(new StudyParticipant(study, 2)));

        assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest(CODE)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(INVALID);
        assertThat(run.getParticipant().getStudent()).isNull();
        assertThat(run.getRedeemedAt()).isNull();
    }

    @Test
    void sixthFailedRedemptionInFiveMinutesIsRateLimitedPerStudent() {
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash("ZZZZ9999"), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.empty());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INVALID);
        }

        assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Too many failed redemption attempts, try again later");
        // The limit is per student: another student is unaffected.
        assertThatThrownBy(() -> service.redeem(secondStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                .hasMessage(INVALID);
        // A valid code is not even looked up while the student is blocked.
        assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest(CODE)))
                .hasMessage("Too many failed redemption attempts, try again later");
        verify(runRepository, never()).findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING);
    }

    @Test
    void successfulRedemptionClearsTheFailedAttempts() {
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash("ZZZZ9999"), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.empty());
        var run = pendingRun();
        run.getParticipant().linkStudent(student);
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.of(run));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                    .hasMessage(INVALID);
        }

        assertThat(service.redeem(firstStudentId, new RedeemAccessCodeRequest(CODE)).status()).isEqualTo("PENDING");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                    .hasMessage(INVALID);
        }
        assertThatThrownBy(() -> service.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                .hasMessage("Too many failed redemption attempts, try again later");
    }

    @Test
    void rateLimitWindowExpiresAfterFiveMinutes() {
        var ticking = new MutableClock(NOW);
        var timed = new StudentExperimentService(runRepository, participantRepository, studentRepository, ticking, "b");
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash("ZZZZ9999"), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.empty());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> timed.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                    .hasMessage(INVALID);
        }
        assertThatThrownBy(() -> timed.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                .hasMessage("Too many failed redemption attempts, try again later");

        ticking.now = NOW.plus(Duration.ofMinutes(5)).plusSeconds(1);
        assertThatThrownBy(() -> timed.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                .hasMessage(INVALID);
    }

    @Test
    void missingStudentAccountIsRejectedGenericallyBeforeLookingUpTheCode() {
        UUID deletedAccount = UUID.randomUUID();
        when(studentRepository.findById(deletedAccount)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem(deletedAccount, new RedeemAccessCodeRequest(CODE)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(INVALID);

        // No code oracle for a deleted account, and the attempt counts as a failure.
        verify(runRepository, never()).findByAccessCodeHashAndStatusForUpdate(any(), any());
        assertThat(service.trackedStudents()).isEqualTo(1);
    }

    @Test
    void parallelFailuresCannotExceedTheLimit() throws Exception {
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash("ZZZZ9999"), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.empty());
        int threads = 10;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var outcomes = new ConcurrentLinkedQueue<String>();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        service.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999"));
                        outcomes.add("redeemed");
                    } catch (BusinessException e) {
                        outcomes.add(e.getMessage());
                    }
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).hasSize(threads);
        assertThat(outcomes.stream().filter(INVALID::equals).count()).isEqualTo(5);
        assertThat(outcomes.stream().filter(TOO_MANY::equals).count()).isEqualTo(5);
        verify(runRepository, atMost(5))
                .findByAccessCodeHashAndStatusForUpdate(AccessCode.hash("ZZZZ9999"), ExperimentRunStatus.PENDING);
    }

    @Test
    void expiredWindowsAreEvicted() {
        var ticking = new MutableClock(NOW);
        var timed = new StudentExperimentService(runRepository, participantRepository, studentRepository, ticking, "b");
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash("ZZZZ9999"), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.empty());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> timed.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                    .hasMessage(INVALID);
        }
        assertThatThrownBy(() -> timed.redeem(secondStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                .hasMessage(INVALID);
        assertThat(timed.trackedStudents()).isEqualTo(2);

        ticking.now = NOW.plus(Duration.ofMinutes(6));
        assertThatThrownBy(() -> timed.redeem(firstStudentId, new RedeemAccessCodeRequest("ZZZZ9999")))
                .hasMessage(INVALID);

        // The lookup happened (the window was reset) and both stale windows were evicted first.
        verify(runRepository, times(7))
                .findByAccessCodeHashAndStatusForUpdate(AccessCode.hash("ZZZZ9999"), ExperimentRunStatus.PENDING);
        assertThat(timed.trackedStudents()).isEqualTo(1);
    }

    // ------------------------------------------------------------------- start

    @Test
    void startActivatesRunRecordsBackendVersionAndIsIdempotent() {
        var run = redeemedRun();
        when(runRepository.findByIdAndParticipantStudentIdForUpdate(run.getId(), firstStudentId)).thenReturn(Optional.of(run));

        ExperimentRunResponse started = service.start(firstStudentId, run.getId());
        ExperimentRunResponse again = service.start(firstStudentId, run.getId());

        assertThat(started.status()).isEqualTo("ACTIVE");
        assertThat(started.startedAt()).isEqualTo(NOW);
        assertThat(again.startedAt()).isEqualTo(NOW);
        assertThat(run.getBackendVersion()).isEqualTo("test-build");
        assertThat(run.getAccessCodeHash()).isNull();
    }

    @Test
    void startAfterTheCodeExpiredMarksTheRunExpiredAndFails() {
        var run = new ExperimentRun(new StudyParticipant(study, 1), protocol,
                protocol.findTask(TaskVariant.TASK_A).orElseThrow(), ExperimentCondition.ASSISTED,
                AccessCode.hash(CODE), NOW.minusSeconds(1), NOW.minus(TTL));
        run.getParticipant().linkStudent(student);
        run.redeem(NOW.minusSeconds(120));
        when(runRepository.findByIdAndParticipantStudentIdForUpdate(run.getId(), firstStudentId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.start(firstStudentId, run.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Access code expired before start");

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.EXPIRED);
        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getBackendVersion()).isNull();
    }

    @Test
    void startRequiresARedeemedRunOwnedByTheStudent() {
        var notRedeemed = pendingRun();
        when(runRepository.findByIdAndParticipantStudentIdForUpdate(notRedeemed.getId(), firstStudentId))
                .thenReturn(Optional.of(notRedeemed));
        assertThatThrownBy(() -> service.start(firstStudentId, notRedeemed.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Run is not ready to start");

        UUID foreign = UUID.randomUUID();
        when(runRepository.findByIdAndParticipantStudentIdForUpdate(foreign, firstStudentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.start(firstStudentId, foreign))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Run not found");
    }

    // ------------------------------------------------------------------ active

    @Test
    void activeReturnsTheFirstRestorableRun() {
        var active = startedRun(NOW.minusSeconds(30));
        when(runRepository.findRestorableByStudentId(firstStudentId, NOW)).thenReturn(List.of(active, redeemedRun()));

        var restored = service.active(firstStudentId);

        assertThat(restored.id()).isEqualTo(active.getId());
        assertThat(restored.status()).isEqualTo("ACTIVE");
        assertThat(restored.promptText()).isEqualTo("Cuenta tu fin de semana");
    }

    @Test
    void activeRestoresARedeemedButNotStartedRun() {
        var run = pendingRun();
        run.getParticipant().linkStudent(student);
        when(runRepository.findByAccessCodeHashAndStatusForUpdate(AccessCode.hash(CODE), ExperimentRunStatus.PENDING))
                .thenReturn(Optional.of(run));
        when(runRepository.findRestorableByStudentId(student.getId(), NOW))
                .thenAnswer(inv -> run.isRedeemedPending() ? List.of(run) : List.of());
        service.redeem(student.getId(), new RedeemAccessCodeRequest("ABCD2345"));

        var restored = service.active(student.getId());

        assertThat(restored.id()).isEqualTo(run.getId());
        assertThat(restored.status()).isEqualTo("PENDING");
    }

    @Test
    void activeIsNotFoundWhenNothingIsRestorable() {
        when(runRepository.findRestorableByStudentId(firstStudentId, NOW)).thenReturn(List.of());

        assertThatThrownBy(() -> service.active(firstStudentId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No experiment run to restore");
    }

    // ---------------------------------------------------------------- complete

    @Test
    void completeStoresTextDurationAppVersionAndIsIdempotentByKey() {
        var run = activeRunStartedAt(NOW.minusSeconds(120));
        when(runRepository.findByIdAndParticipantStudentIdForUpdate(run.getId(), firstStudentId)).thenReturn(Optional.of(run));
        UUID key = UUID.randomUUID();
        var request = new CompleteExperimentRequest("Texto final", 100_000L, key, "1.4.2");

        var response = service.complete(firstStudentId, run.getId(), request);
        var repeated = service.complete(firstStudentId, run.getId(),
                new CompleteExperimentRequest("Otro texto", 5L, key, "9.9.9"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(repeated.id()).isEqualTo(response.id());
        assertThat(run.getFinalText()).isEqualTo("Texto final");
        assertThat(run.getDurationMs()).isEqualTo(100_000L);
        assertThat(run.getAppVersion()).isEqualTo("1.4.2");
        assertThat(run.getCompletedAt()).isEqualTo(NOW);
        assertThat(run.getIncidentCount()).isZero();
        verify(runRepository).saveAndFlush(run);
    }

    @Test
    void inconsistentDurationIsRecordedAsIncidentNotRejected() {
        var run = activeRunStartedAt(clock.instant().minusSeconds(60));
        var request = new CompleteExperimentRequest("Texto final", 20 * 60_000L, UUID.randomUUID(), "debug");

        var response = service.complete(student.getId(), run.getId(), request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(run.getDurationMs()).isEqualTo(20 * 60_000L);
        assertThat(run.getIncidentCount()).isEqualTo(1);
        assertThat(run.getFailureReason()).isEqualTo("DURATION_INCONSISTENT");
    }

    @Test
    void durationWithinToleranceIsNotAnIncident() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));
        var request = new CompleteExperimentRequest("Texto final", 60_000L + 300_000L, UUID.randomUUID(), "debug");

        service.complete(student.getId(), run.getId(), request);

        assertThat(run.getIncidentCount()).isZero();
        assertThat(run.getFailureReason()).isNull();
        assertThat(run.getIncidents()).isEmpty();
    }

    @Test
    void plausibleDurationRecordsNoIncident() {
        var run = activeRunStartedAt(NOW.minusSeconds(120));
        // 20 palabras en 60 s = 20 ppm, dentro de lo transcurrido en el servidor.
        var request = new CompleteExperimentRequest("uno dos tres cuatro cinco seis siete ocho nueve diez "
                + "once doce trece catorce quince dieciseis diecisiete dieciocho diecinueve veinte",
                60_000L, UUID.randomUUID(), "debug");

        var response = service.complete(student.getId(), run.getId(), request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(run.getIncidentCount()).isZero();
        assertThat(run.getIncidents()).isEmpty();
    }

    @Test
    void subSecondDurationIsRecordedAsImplausiblyShortNotRejected() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));
        var request = new CompleteExperimentRequest("Texto final", 999L, UUID.randomUUID(), "debug");

        var response = service.complete(student.getId(), run.getId(), request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(run.getDurationMs()).isEqualTo(999L);
        assertThat(run.getIncidentCount()).isEqualTo(1);
        assertThat(run.getFailureReason()).isEqualTo("DURATION_IMPLAUSIBLY_SHORT");
        assertThat(run.getIncidents()).singleElement().satisfies(incident -> {
            assertThat(incident.getReason()).isEqualTo("DURATION_IMPLAUSIBLY_SHORT");
            assertThat(incident.getCreatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void moreThanTwoHundredWordsPerMinuteIsRecordedAsImplausiblyShort() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));
        // 21 palabras en 6 s = 210 ppm (> 200); la duracion sigue por debajo de lo transcurrido en el servidor.
        String words = "uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince "
                + "dieciseis diecisiete dieciocho diecinueve veinte veintiuno";
        var request = new CompleteExperimentRequest(words, 6_000L, UUID.randomUUID(), "debug");

        service.complete(student.getId(), run.getId(), request);

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
        assertThat(run.getIncidentCount()).isEqualTo(1);
        assertThat(run.getFailureReason()).isEqualTo("DURATION_IMPLAUSIBLY_SHORT");

        // Exactly 200 ppm is still plausible: 20 words in 6 s.
        var boundary = activeRunStartedAt(NOW.minusSeconds(60));
        service.complete(student.getId(), boundary.getId(), new CompleteExperimentRequest(
                "uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince "
                        + "dieciseis diecisiete dieciocho diecinueve veinte", 6_000L, UUID.randomUUID(), "debug"));
        assertThat(boundary.getIncidentCount()).isZero();
    }

    @Test
    void bothDurationIncidentsCanCoexistOnOneCompletion() {
        // Una ejecucion iniciada "en el futuro" (reloj del servidor atrasado) hace que 500 ms supere lo
        // transcurrido y a la vez sea implausiblemente corta: ambas incidencias quedan en el historial.
        var late = activeRunStartedAt(NOW.plusSeconds(600));

        service.complete(student.getId(), late.getId(), new CompleteExperimentRequest("Texto", 500L, UUID.randomUUID(), "d"));

        assertThat(late.getIncidents()).extracting(incident -> incident.getReason())
                .containsExactly("DURATION_INCONSISTENT", "DURATION_IMPLAUSIBLY_SHORT");
        assertThat(late.getIncidentCount()).isEqualTo(2);
        assertThat(late.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
    }

    @Test
    void completeRequiresAnActiveRun() {
        var run = redeemedRun();
        when(runRepository.findByIdAndParticipantStudentIdForUpdate(run.getId(), firstStudentId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.complete(firstStudentId, run.getId(),
                new CompleteExperimentRequest("Texto", 1_000L, UUID.randomUUID(), "debug")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Run is not active");
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.PENDING);
    }

    @Test
    void completedRunRejectsADifferentCompletionKey() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));
        service.complete(firstStudentId, run.getId(), new CompleteExperimentRequest("Texto", 1_000L, UUID.randomUUID(), "d"));

        assertThatThrownBy(() -> service.complete(firstStudentId, run.getId(),
                new CompleteExperimentRequest("Texto", 1_000L, UUID.randomUUID(), "d")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Run is not active");
        assertThat(run.getFinalText()).isEqualTo("Texto");
    }

    @Test
    void completionKeyUsedByAnotherRunIsAConflict() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));
        var duplicate = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("duplicate key"),
                        "UK_RUNS_COMPLETION_KEY"));
        when(runRepository.saveAndFlush(run)).thenThrow(duplicate);

        assertThatThrownBy(() -> service.complete(firstStudentId, run.getId(),
                new CompleteExperimentRequest("Texto", 1_000L, UUID.randomUUID(), "d")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Completion key already used by another run");
    }

    @Test
    void unrecognisedIntegrityViolationOnCompletionIsRethrown() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));
        var other = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("check"), "ck_run_duration"));
        when(runRepository.saveAndFlush(run)).thenThrow(other);

        assertThatThrownBy(() -> service.complete(firstStudentId, run.getId(),
                new CompleteExperimentRequest("Texto", 1_000L, UUID.randomUUID(), "d")))
                .isSameAs(other);
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void studentCancelsAnActiveRunWithAnAllowedReason() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));

        service.cancel(firstStudentId, run.getId(), new CancelExperimentRequest(CancelExperimentRequest.Reason.ABANDONED));
        service.cancel(firstStudentId, run.getId(), new CancelExperimentRequest(CancelExperimentRequest.Reason.TECHNICAL_PROBLEM));

        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.CANCELLED);
        assertThat(run.getFailureReason()).isEqualTo("Cancelled by the student: abandoned the task");
        assertThat(run.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void completedRunCannotBeCancelledByTheStudent() {
        var run = activeRunStartedAt(NOW.minusSeconds(60));
        service.complete(firstStudentId, run.getId(), new CompleteExperimentRequest("Texto", 1_000L, UUID.randomUUID(), "d"));

        assertThatThrownBy(() -> service.cancel(firstStudentId, run.getId(),
                new CancelExperimentRequest(CancelExperimentRequest.Reason.INTERRUPTED)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only pending or active runs can be cancelled");
        assertThat(run.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
    }

    @Test
    void responsesNeverCarryIdentityOrOtherParticipantsData() {
        for (var component : ExperimentRunResponse.class.getRecordComponents()) {
            assertThat(component.getName().toLowerCase())
                    .as("component %s", component.getName())
                    .doesNotContain("student", "name", "email", "username", "hash", "finaltext", "participantid");
        }
    }

    // ----------------------------------------------------------------- helpers

    private ExperimentRun pendingRun() {
        return new ExperimentRun(new StudyParticipant(study, 1), protocol,
                protocol.findTask(TaskVariant.TASK_A).orElseThrow(), ExperimentCondition.ASSISTED,
                AccessCode.hash(CODE), NOW.plus(TTL), NOW);
    }

    private ExperimentRun redeemedRun() {
        var run = pendingRun();
        run.getParticipant().linkStudent(student);
        run.redeem(NOW);
        return run;
    }

    private ExperimentRun startedRun(Instant startedAt) {
        var run = redeemedRun();
        run.start(startedAt);
        return run;
    }

    /** ACTIVE run owned by {@code student}, already reachable through the repository. */
    private ExperimentRun activeRunStartedAt(Instant startedAt) {
        var run = startedRun(startedAt);
        when(runRepository.findByIdAndParticipantStudentIdForUpdate(run.getId(), student.getId())).thenReturn(Optional.of(run));
        return run;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
