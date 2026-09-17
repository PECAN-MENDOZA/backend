package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.sentencetest.application.dto.StartAttemptRequest;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAssignment;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.SentenceTestRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAssignmentRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestSentenceRepository;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Dos POST simultaneos de un mismo alumno a dos pruebas distintas deben producir exactamente un
 * intento IN_PROGRESS; el bloqueo pesimista sobre la fila del alumno en startAttempt serializa las
 * transacciones, y el catch de DataIntegrityViolationException es el respaldo final si el indice
 * unico parcial de la base de datos rechaza igual un segundo intento en curso.
 */
@SpringBootTest
class StudentTestConcurrencyTests {

    @Autowired
    private StudentTestService studentTestService;

    @Autowired
    private SentenceTestRepository testRepository;

    @Autowired
    private TestSentenceRepository sentenceRepository;

    @Autowired
    private TestAssignmentRepository assignmentRepository;

    @Autowired
    private TestAttemptRepository attemptRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ResearcherRepository researcherRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID studentId;
    private UUID testAId;
    private UUID testBId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        transactionTemplate.executeWithoutResult(status -> {
            Researcher researcher = researcherRepository.save(new Researcher(suffix + "@lab.edu", "hash"));
            Student student = studentRepository.save(new Student("alumno-" + suffix, "Colegio", "hash"));
            SentenceTest testA = new SentenceTest("CONC-A-" + suffix, "Prueba A", researcher.getId());
            testA.activate(1, now);
            testA = testRepository.save(testA);
            sentenceRepository.save(new TestSentence(testA, 1, SentenceKind.FREE, "Oracion A", Assistance.UNASSISTED));
            SentenceTest testB = new SentenceTest("CONC-B-" + suffix, "Prueba B", researcher.getId());
            testB.activate(1, now);
            testB = testRepository.save(testB);
            sentenceRepository.save(new TestSentence(testB, 1, SentenceKind.FREE, "Oracion B", Assistance.UNASSISTED));
            assignmentRepository.save(new TestAssignment(testA, student, null, researcher.getId(), now));
            assignmentRepository.save(new TestAssignment(testB, student, null, researcher.getId(), now));
            studentId = student.getId();
            testAId = testA.getId();
            testBId = testB.getId();
        });
    }

    @Test
    void onlyOneOfTwoSimultaneousAttemptsWinsForTheSameStudent() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Outcome>> calls = List.of(
                    outcomeOf(() -> studentTestService.startAttempt(studentId, testAId, new StartAttemptRequest("app-1"))),
                    outcomeOf(() -> studentTestService.startAttempt(studentId, testBId, new StartAttemptRequest("app-1"))));
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }

            long succeeded = outcomes.stream().filter(o -> o.error() == null).count();
            long conflicted = outcomes.stream().filter(o -> o.error() instanceof ConflictException).count();
            assertThat(succeeded).isEqualTo(1);
            assertThat(conflicted).isEqualTo(1);
            assertThat(outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow().error())
                    .hasMessageContaining("Another test is in progress");

            assertThat(attemptRepository.findByStudentIdAndStatus(studentId, AttemptStatus.IN_PROGRESS)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Outcome> outcomeOf(Callable<StudentTestService.StartedAttempt> call) {
        return () -> {
            try {
                return new Outcome(call.call(), null);
            } catch (RuntimeException e) {
                return new Outcome(null, e);
            }
        };
    }

    private record Outcome(StudentTestService.StartedAttempt attempt, RuntimeException error) {
    }
}
