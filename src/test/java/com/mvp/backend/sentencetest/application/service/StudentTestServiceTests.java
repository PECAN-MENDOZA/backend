package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.sentencetest.application.dto.AssignedTestResponse;
import com.mvp.backend.sentencetest.application.dto.AttemptResponse;
import com.mvp.backend.sentencetest.application.dto.FinishSentenceRequest;
import com.mvp.backend.sentencetest.application.dto.StartAttemptRequest;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AttemptCancelReason;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAssignment;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.SentenceTestRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAssignmentRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestResponseRepository;
import com.mvp.backend.sentencetest.domain.repository.TestSentenceRepository;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

@ExtendWith(MockitoExtension.class)
class StudentTestServiceTests {

    @Mock private SentenceTestRepository testRepository;
    @Mock private TestSentenceRepository sentenceRepository;
    @Mock private TestAssignmentRepository assignmentRepository;
    @Mock private TestAttemptRepository attemptRepository;
    @Mock private TestResponseRepository responseRepository;
    @Mock private StudentRepository studentRepository;

    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");
    private StudentTestService service;
    private Student student;
    private SentenceTest test;
    private List<TestSentence> sentences;

    @BeforeEach
    void setUp() {
        service = new StudentTestService(testRepository, sentenceRepository, assignmentRepository, attemptRepository,
                responseRepository, studentRepository, Clock.fixed(now, ZoneOffset.UTC), "backend-test");
        student = new Student("tigre-07", "Colegio", "hash");
        test = new SentenceTest("PRUEBA-01", "Dictado", UUID.randomUUID());
        test.activate(2, now);
        sentences = List.of(
                new TestSentence(test, 1, SentenceKind.DICTATED, "El perro corre.", Assistance.ASSISTED),
                new TestSentence(test, 2, SentenceKind.FREE, "Una oracion sobre tu mascota", Assistance.UNASSISTED));
    }

    @Test
    void startAttemptReturnsSlotsWithoutReferenceText() {
        when(studentRepository.findByIdForUpdate(student.getId())).thenReturn(Optional.of(student));
        when(testRepository.findById(test.getId())).thenReturn(Optional.of(test));
        when(assignmentRepository.existsByTestIdAndStudentId(test.getId(), student.getId())).thenReturn(true);
        when(attemptRepository.findByStudentIdAndStatus(student.getId(), AttemptStatus.IN_PROGRESS)).thenReturn(Optional.empty());
        when(sentenceRepository.findByTestIdOrderByPositionAsc(test.getId())).thenReturn(sentences);
        when(attemptRepository.saveAndFlush(any(TestAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(any())).thenReturn(0L);

        StudentTestService.StartedAttempt started =
                service.startAttempt(student.getId(), test.getId(), new StartAttemptRequest("app-1"));
        AttemptResponse response = started.attempt();

        assertThat(started.created()).isTrue();
        assertThat(response.nextPosition()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        assertThat(response.sentences()).extracting(AttemptResponse.SentenceSlot::assistance).containsExactly("ASSISTED", "UNASSISTED");
        assertThat(response.toString()).doesNotContain("perro").doesNotContain("mascota").doesNotContain("DICTATED");
    }

    @Test
    void startAttemptResumesAttemptInProgressOfSameTest() {
        TestAttempt inProgress = new TestAttempt(test, student, "app", "b", now);
        when(studentRepository.findByIdForUpdate(student.getId())).thenReturn(Optional.of(student));
        when(testRepository.findById(test.getId())).thenReturn(Optional.of(test));
        when(assignmentRepository.existsByTestIdAndStudentId(test.getId(), student.getId())).thenReturn(true);
        when(attemptRepository.findByStudentIdAndStatus(student.getId(), AttemptStatus.IN_PROGRESS)).thenReturn(Optional.of(inProgress));
        when(sentenceRepository.findByTestIdOrderByPositionAsc(test.getId())).thenReturn(sentences);
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(inProgress.getId())).thenReturn(1L);

        StudentTestService.StartedAttempt started =
                service.startAttempt(student.getId(), test.getId(), new StartAttemptRequest("app-1"));

        assertThat(started.created()).isFalse();
        assertThat(started.attempt().attemptId()).isEqualTo(inProgress.getId());
        assertThat(started.attempt().nextPosition()).isEqualTo(2);
    }

    @Test
    void startAttemptRejectsWhenAnotherTestIsInProgress() {
        SentenceTest other = new SentenceTest("PRUEBA-02", "Otra", UUID.randomUUID());
        other.activate(1, now);
        TestAttempt inProgress = new TestAttempt(other, student, "app", "b", now);
        when(studentRepository.findByIdForUpdate(student.getId())).thenReturn(Optional.of(student));
        when(testRepository.findById(test.getId())).thenReturn(Optional.of(test));
        when(assignmentRepository.existsByTestIdAndStudentId(test.getId(), student.getId())).thenReturn(true);
        when(attemptRepository.findByStudentIdAndStatus(student.getId(), AttemptStatus.IN_PROGRESS)).thenReturn(Optional.of(inProgress));

        assertThatThrownBy(() -> service.startAttempt(student.getId(), test.getId(), new StartAttemptRequest("app-1")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Another test is in progress");
    }

    @Test
    void unassignedTestIsForbidden() {
        when(studentRepository.findByIdForUpdate(student.getId())).thenReturn(Optional.of(student));
        when(testRepository.findById(test.getId())).thenReturn(Optional.of(test));
        when(assignmentRepository.existsByTestIdAndStudentId(test.getId(), student.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.startAttempt(student.getId(), test.getId(), new StartAttemptRequest("app-1")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void startAttemptOnDraftTestIsConflict() {
        SentenceTest draft = new SentenceTest("PRUEBA-03", "Borrador", UUID.randomUUID());
        when(studentRepository.findByIdForUpdate(student.getId())).thenReturn(Optional.of(student));
        when(testRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(assignmentRepository.existsByTestIdAndStudentId(draft.getId(), student.getId())).thenReturn(true);
        when(attemptRepository.findByStudentIdAndStatus(student.getId(), AttemptStatus.IN_PROGRESS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startAttempt(student.getId(), draft.getId(), new StartAttemptRequest("app-1")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Test is not active");
    }

    @Test
    void startAttemptRejectsSecondAttemptOnACompletedTest() {
        when(studentRepository.findByIdForUpdate(student.getId())).thenReturn(Optional.of(student));
        when(testRepository.findById(test.getId())).thenReturn(Optional.of(test));
        when(assignmentRepository.existsByTestIdAndStudentId(test.getId(), student.getId())).thenReturn(true);
        when(attemptRepository.findByStudentIdAndStatus(student.getId(), AttemptStatus.IN_PROGRESS)).thenReturn(Optional.empty());
        when(attemptRepository.findByTestIdAndStudentIdAndStatus(test.getId(), student.getId(), AttemptStatus.COMPLETED))
                .thenReturn(Optional.of(new TestAttempt(test, student, "app", "b", now)));

        assertThatThrownBy(() -> service.startAttempt(student.getId(), test.getId(), new StartAttemptRequest("app-1")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Test already completed");
    }

    @Test
    void assignedTestsHidesClosedTestWithoutAttemptAndShowsCompletedWithOne() {
        SentenceTest closedNoAttempt = new SentenceTest("PRUEBA-04", "Cerrada sin intento", UUID.randomUUID());
        closedNoAttempt.activate(1, now);
        closedNoAttempt.close(now);
        SentenceTest closedWithAttempt = new SentenceTest("PRUEBA-05", "Cerrada con intento", UUID.randomUUID());
        closedWithAttempt.activate(1, now);
        closedWithAttempt.close(now);
        TestAttempt completedAttempt = new TestAttempt(closedWithAttempt, student, "app", "b", now);
        completedAttempt.complete(now);
        TestAssignment assignmentNoAttempt = new TestAssignment(closedNoAttempt, student, null, UUID.randomUUID(), now);
        TestAssignment assignmentWithAttempt = new TestAssignment(closedWithAttempt, student, null, UUID.randomUUID(), now);
        when(assignmentRepository.findByStudentIdOrderByAssignedAtDesc(student.getId()))
                .thenReturn(List.of(assignmentNoAttempt, assignmentWithAttempt));
        when(attemptRepository.findByStudentIdOrderByStartedAtDesc(student.getId())).thenReturn(List.of(completedAttempt));
        when(sentenceRepository.countByTestId(closedWithAttempt.getId())).thenReturn(1L);

        List<AssignedTestResponse> assigned = service.assignedTests(student.getId());

        assertThat(assigned).hasSize(1);
        assertThat(assigned.get(0).testId()).isEqualTo(closedWithAttempt.getId());
        assertThat(assigned.get(0).status()).isEqualTo("COMPLETED");
    }

    @Test
    void assignedTestsHidesClosedTestWhoseOnlyAttemptWasCancelled() {
        SentenceTest closed = new SentenceTest("PRUEBA-06", "Cerrada con intento cancelado", UUID.randomUUID());
        closed.activate(1, now);
        closed.close(now);
        TestAttempt cancelled = new TestAttempt(closed, student, "app", "b", now);
        cancelled.cancel(AttemptCancelReason.ABANDONED, now);
        when(assignmentRepository.findByStudentIdOrderByAssignedAtDesc(student.getId()))
                .thenReturn(List.of(new TestAssignment(closed, student, null, UUID.randomUUID(), now)));
        when(attemptRepository.findByStudentIdOrderByStartedAtDesc(student.getId())).thenReturn(List.of(cancelled));

        assertThat(service.assignedTests(student.getId())).isEmpty();
    }

    @Test
    void sentencesMustBeStartedAndFinishedInOrder() {
        TestAttempt attempt = new TestAttempt(test, student, "app", "b", now);
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.startSentence(student.getId(), attempt.getId(), 2))
                .isInstanceOf(ConflictException.class).hasMessageContaining("out of order");
    }

    @Test
    void finishIsIdempotentByCompletionKeyAndCompletesAttemptOnLastSentence() {
        TestAttempt attempt = new TestAttempt(test, student, "app", "b", now);
        TestResponse second = new TestResponse(attempt, sentences.get(1), now);
        UUID key = UUID.randomUUID();
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(responseRepository.findByAttemptIdAndPosition(attempt.getId(), 2)).thenReturn(Optional.of(second));
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId())).thenReturn(1L, 2L);
        when(sentenceRepository.countByTestId(test.getId())).thenReturn(2L);
        FinishSentenceRequest request = new FinishSentenceRequest("Mi gato duerme", 800L, 6000L, 0, 0, 0, 0, false, key);

        var first = service.finishSentence(student.getId(), attempt.getId(), 2, request);
        assertThat(first.nextPosition()).isNull();
        assertThat(first.attemptStatus()).isEqualTo("COMPLETED");
        assertThat(attempt.isCompleted()).isTrue();
        assertThat(second.getAutoErrorCount()).isNull(); // libre: sin conteo automatico

        var again = service.finishSentence(student.getId(), attempt.getId(), 2, request);
        assertThat(again).isEqualTo(first);

        assertThatThrownBy(() -> service.finishSentence(student.getId(), attempt.getId(), 2,
                new FinishSentenceRequest("otro", 800L, 6000L, 0, 0, 0, 0, false, UUID.randomUUID())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void dictatedSentenceGetsAutomaticErrorCount() {
        TestAttempt attempt = new TestAttempt(test, student, "app", "b", now);
        TestResponse first = new TestResponse(attempt, sentences.get(0), now);
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(responseRepository.findByAttemptIdAndPosition(attempt.getId(), 1)).thenReturn(Optional.of(first));
        when(responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId())).thenReturn(0L, 1L);
        when(sentenceRepository.countByTestId(test.getId())).thenReturn(2L);

        var result = service.finishSentence(student.getId(), attempt.getId(), 1,
                new FinishSentenceRequest("El pero corre", 500L, 4000L, 1, 0, 1, 0, false, UUID.randomUUID()));

        assertThat(result.nextPosition()).isEqualTo(2);
        assertThat(first.getAutoErrorCount()).isEqualTo(1);
        assertThat(first.getAutoErrorDetail()).contains("\"SUSTITUCION\"").contains("\"perro\"");
    }

    @Test
    void attemptOfAnotherStudentIsForbidden() {
        TestAttempt attempt = new TestAttempt(test, student, "app", "b", now);
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.startSentence(UUID.randomUUID(), attempt.getId(), 1))
                .isInstanceOf(ForbiddenException.class);
    }
}
