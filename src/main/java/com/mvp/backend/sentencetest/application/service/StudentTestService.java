package com.mvp.backend.sentencetest.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.sentencetest.application.dto.AssignedTestResponse;
import com.mvp.backend.sentencetest.application.dto.AttemptResponse;
import com.mvp.backend.sentencetest.application.dto.CancelAttemptRequest;
import com.mvp.backend.sentencetest.application.dto.FinishSentenceRequest;
import com.mvp.backend.sentencetest.application.dto.FinishSentenceResponse;
import com.mvp.backend.sentencetest.application.dto.StartAttemptRequest;
import com.mvp.backend.sentencetest.application.dto.StartSentenceResponse;
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
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/** Flujo del alumno: pruebas asignadas, intento, Comenzar/Terminar por oracion, cancelacion. */
@Service
public class StudentTestService {

    /** Resultado de iniciar un intento: created indica si se creo ahora (201) o se retomo uno en curso (200). */
    public record StartedAttempt(AttemptResponse attempt, boolean created) {
    }

    private final SentenceTestRepository testRepository;
    private final TestSentenceRepository sentenceRepository;
    private final TestAssignmentRepository assignmentRepository;
    private final TestAttemptRepository attemptRepository;
    private final TestResponseRepository responseRepository;
    private final StudentRepository studentRepository;
    private final Clock clock;
    private final String backendVersion;

    public StudentTestService(
            SentenceTestRepository testRepository,
            TestSentenceRepository sentenceRepository,
            TestAssignmentRepository assignmentRepository,
            TestAttemptRepository attemptRepository,
            TestResponseRepository responseRepository,
            StudentRepository studentRepository,
            Clock clock,
            @Value("${app.build-version:local}") String backendVersion) {
        this.testRepository = testRepository;
        this.sentenceRepository = sentenceRepository;
        this.assignmentRepository = assignmentRepository;
        this.attemptRepository = attemptRepository;
        this.responseRepository = responseRepository;
        this.studentRepository = studentRepository;
        this.clock = clock;
        this.backendVersion = backendVersion;
    }

    /** Pruebas asignadas con su estado para el alumno. Una prueba cerrada sin intento no se lista. */
    @Transactional(readOnly = true)
    public List<AssignedTestResponse> assignedTests(UUID studentId) {
        List<TestAttempt> attempts = attemptRepository.findByStudentIdOrderByStartedAtDesc(studentId);
        return assignmentRepository.findByStudentIdOrderByAssignedAtDesc(studentId).stream()
                .map(TestAssignment::getTest)
                .filter(test -> test.isActive() || hasAttempt(attempts, test))
                .map(test -> new AssignedTestResponse(test.getId(), test.getCode(), test.getTitle(),
                        (int) sentenceRepository.countByTestId(test.getId()), assignedStatus(attempts, test)))
                .toList();
    }

    /**
     * El bloqueo pesimista sobre la fila del alumno serializa los arranques concurrentes de ese
     * mismo alumno; el catch de DataIntegrityViolationException es el ultimo resguardo si, aun asi,
     * el indice unico parcial de la base de datos rechaza un segundo intento en curso.
     */
    @Transactional
    public StartedAttempt startAttempt(UUID studentId, UUID testId, StartAttemptRequest request) {
        Student student = studentRepository.findByIdForUpdate(studentId)
                .orElseThrow(() -> new NotFoundException("Student not found"));
        SentenceTest test = testRepository.findById(testId)
                .orElseThrow(() -> new NotFoundException("Test not found"));
        if (!assignmentRepository.existsByTestIdAndStudentId(testId, studentId)) {
            throw new ForbiddenException("Test is not assigned to this student");
        }
        Optional<TestAttempt> inProgress = attemptRepository.findByStudentIdAndStatus(studentId, AttemptStatus.IN_PROGRESS);
        if (inProgress.isPresent()) {
            TestAttempt current = inProgress.get();
            if (!current.getTest().getId().equals(testId)) {
                throw new ConflictException("Another test is in progress");
            }
            return new StartedAttempt(toAttemptResponse(current), false);
        }
        if (!test.isActive()) {
            throw new ConflictException("Test is not active");
        }
        if (attemptRepository.findByTestIdAndStudentIdAndStatus(testId, studentId, AttemptStatus.COMPLETED).isPresent()) {
            throw new ConflictException("Test already completed");
        }
        TestAttempt attempt;
        try {
            attempt = attemptRepository.saveAndFlush(
                    new TestAttempt(test, student, request.appVersion(), backendVersion, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Another test is in progress");
        }
        return new StartedAttempt(toAttemptResponse(attempt), true);
    }

    /** Comenzar una oracion. Idempotente: si ya esta comenzada y no terminada devuelve la misma respuesta. */
    @Transactional
    public StartSentenceResponse startSentence(UUID studentId, UUID attemptId, int position) {
        TestAttempt attempt = ownedAttemptForUpdate(studentId, attemptId);
        attempt.requireInProgress();
        int next = nextPosition(attempt);
        Optional<TestResponse> existing = responseRepository.findByAttemptIdAndPosition(attemptId, position);
        if (existing.isPresent() && !existing.get().isFinished()) {
            TestResponse response = existing.get();
            return new StartSentenceResponse(response.getId(), position, response.getSentence().getAssistance().name(), true);
        }
        if (position != next) {
            throw new ConflictException("Sentence out of order: expected " + next);
        }
        TestSentence sentence = sentenceRepository.findByTestIdAndPosition(attempt.getTest().getId(), position)
                .orElseThrow(() -> new NotFoundException("Sentence not found"));
        TestResponse response = responseRepository.save(new TestResponse(attempt, sentence, clock.instant()));
        return new StartSentenceResponse(response.getId(), position, sentence.getAssistance().name(), false);
    }

    /**
     * Terminar una oracion. La misma completionKey sobre una oracion ya terminada devuelve la misma
     * respuesta (reintento del teclado); otra clave es un conflicto. Al terminar la ultima se completa el intento.
     */
    @Transactional
    public FinishSentenceResponse finishSentence(UUID studentId, UUID attemptId, int position, FinishSentenceRequest request) {
        TestAttempt attempt = ownedAttemptForUpdate(studentId, attemptId);
        TestResponse response = responseRepository.findByAttemptIdAndPosition(attemptId, position)
                .orElseThrow(() -> new ConflictException("Sentence not started"));
        long total = sentenceRepository.countByTestId(attempt.getTest().getId());
        if (response.isFinished()) {
            if (response.matchesCompletionKey(request.completionKey())) {
                return finishResponse(attempt, response, total);
            }
            throw new ConflictException("Sentence already finished");
        }
        attempt.requireInProgress();
        if (position != nextPosition(attempt)) {
            throw new ConflictException("Sentence out of order");
        }
        Instant now = clock.instant();
        response.finish(request.finalText(), request.firstKeyOffsetMs(), request.finishedOffsetMs(), request.skipped(),
                new TestResponse.Counters(request.suggestionsOffered(), request.suggestionsAccepted(),
                        request.suggestionsRejected(), request.suggestionsUndone()),
                request.completionKey(), now);
        if (response.getSentence().getKind() == SentenceKind.DICTATED) {
            SentenceAligner.Alignment alignment =
                    SentenceAligner.align(response.getSentence().getReferenceText(), response.getFinalText());
            response.recordAutoErrors(alignment.errorCount(), alignment.toJson());
        }
        long finished = responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attemptId);
        if (finished >= total) {
            attempt.complete(now);
        }
        return finishResponse(attempt, response, total);
    }

    @Transactional
    public void cancelAttempt(UUID studentId, UUID attemptId, CancelAttemptRequest request) {
        TestAttempt attempt = ownedAttemptForUpdate(studentId, attemptId);
        attempt.cancel(request.reason(), clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<TestAttempt> activeAttempt(UUID studentId) {
        return attemptRepository.findByStudentIdAndStatus(studentId, AttemptStatus.IN_PROGRESS);
    }

    /** Respuesta del alumno para ligar una correccion; falla si no le pertenece. */
    @Transactional(readOnly = true)
    public TestResponse responseForCorrection(UUID studentId, UUID responseId) {
        TestResponse response = responseRepository.findById(responseId)
                .orElseThrow(() -> new NotFoundException("Sentence response not found"));
        if (!response.getAttempt().getStudent().getId().equals(studentId)) {
            throw new ForbiddenException("Sentence response belongs to another student");
        }
        return response;
    }

    /** Un intento CANCELLED no cuenta: una prueba CLOSED cuyo unico intento se cancelo ya no puede iniciarse. */
    private static boolean hasAttempt(List<TestAttempt> attempts, SentenceTest test) {
        return attempts.stream()
                .anyMatch(a -> a.getTest().getId().equals(test.getId()) && a.getStatus() != AttemptStatus.CANCELLED);
    }

    /** COMPLETED si existe un intento completado; IN_PROGRESS si hay uno en curso; si no, PENDING. */
    private static String assignedStatus(List<TestAttempt> attempts, SentenceTest test) {
        List<TestAttempt> own = attempts.stream().filter(a -> a.getTest().getId().equals(test.getId())).toList();
        if (own.stream().anyMatch(TestAttempt::isCompleted)) {
            return AttemptStatus.COMPLETED.name();
        }
        if (own.stream().anyMatch(TestAttempt::isInProgress)) {
            return AttemptStatus.IN_PROGRESS.name();
        }
        return "PENDING";
    }

    private FinishSentenceResponse finishResponse(TestAttempt attempt, TestResponse response, long total) {
        long finished = responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId());
        Integer next = attempt.isInProgress() && finished < total ? (int) finished + 1 : null;
        return new FinishSentenceResponse(response.getId(), next, attempt.getStatus().name());
    }

    private int nextPosition(TestAttempt attempt) {
        return (int) responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId()) + 1;
    }

    private TestAttempt ownedAttemptForUpdate(UUID studentId, UUID attemptId) {
        TestAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new NotFoundException("Attempt not found"));
        if (!attempt.getStudent().getId().equals(studentId)) {
            throw new ForbiddenException("Attempt belongs to another student");
        }
        return attempt;
    }

    private AttemptResponse toAttemptResponse(TestAttempt attempt) {
        SentenceTest test = attempt.getTest();
        List<AttemptResponse.SentenceSlot> slots = sentenceRepository.findByTestIdOrderByPositionAsc(test.getId()).stream()
                .map(s -> new AttemptResponse.SentenceSlot(s.getPosition(), s.getAssistance().name()))
                .toList();
        long finished = responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId());
        Integer next = attempt.isInProgress() && finished < slots.size() ? (int) finished + 1 : null;
        return new AttemptResponse(attempt.getId(), test.getId(), test.getCode(), test.getTitle(), next,
                attempt.getStatus().name(), slots);
    }
}
