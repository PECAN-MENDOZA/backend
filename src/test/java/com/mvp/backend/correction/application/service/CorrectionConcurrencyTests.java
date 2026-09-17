package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.mvp.backend.correction.application.dto.CorrectionSessionResponse;
import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.sentencetest.application.dto.FinishSentenceRequest;
import com.mvp.backend.sentencetest.application.dto.StartAttemptRequest;
import com.mvp.backend.sentencetest.application.service.StudentTestService;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AutoErrorDetail;
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
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Carreras sobre H2 con el gestor de transacciones real (mismo estilo que StudentTestConcurrencyTests):
 * la llamada a la IA corre fuera de toda transaccion, asi que el intento solo se modifica bajo su bloqueo
 * de fila en la fase de escritura, en serie con Terminar.
 */
@SpringBootTest
class CorrectionConcurrencyTests {

    @Autowired
    private CorrectionService correctionService;

    @Autowired
    private StudentTestService studentTestService;

    @Autowired
    private CorrectionSessionRepository sessionRepository;

    @Autowired
    private SentenceTestRepository testRepository;

    @Autowired
    private TestSentenceRepository sentenceRepository;

    @Autowired
    private TestAssignmentRepository assignmentRepository;

    @Autowired
    private TestAttemptRepository attemptRepository;

    @Autowired
    private TestResponseRepository responseRepository;

    @Autowired
    private ResearcherRepository researcherRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private AiCorrectionClient aiCorrectionClient;

    private UUID studentId;
    private UUID testId;
    private UUID attemptId;
    private UUID responseId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        transactionTemplate.executeWithoutResult(status -> {
            Researcher researcher = researcherRepository.save(new Researcher(suffix + "@lab.edu", "hash"));
            Student student = studentRepository.save(new Student("alumno-" + suffix, "Colegio", "hash"));
            SentenceTest test = new SentenceTest("CORR-" + suffix, "Dictado", researcher.getId());
            test.activate(2, now);
            test = testRepository.save(test);
            sentenceRepository.save(new TestSentence(test, 1, SentenceKind.DICTATED, "El nino iba al patio.", Assistance.ASSISTED));
            sentenceRepository.save(new TestSentence(test, 2, SentenceKind.FREE, "Cuenta tu fin de semana", Assistance.UNASSISTED));
            assignmentRepository.save(new TestAssignment(test, student, null, researcher.getId(), now));
            studentId = student.getId();
            testId = test.getId();
        });
        attemptId = studentTestService.startAttempt(studentId, testId, new StartAttemptRequest("app-1")).attempt().attemptId();
        responseId = studentTestService.startSentence(studentId, attemptId, 1).responseId();
    }

    @Test
    void correctionArrivingAfterTheSentenceIsFinishedIsRejectedAndDoesNotTouchTheAttempt() throws Exception {
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch aiRelease = new CountDownLatch(1);
        when(aiCorrectionClient.correct(anyString(), any())).thenAnswer(invocation -> {
            aiEntered.countDown();
            assertThat(aiRelease.await(15, TimeUnit.SECONDS)).isTrue();
            return new AiCorrectionResponse(studentId, "texto corregido", 10, List.of(), "beto-lora-1.2");
        });
        var finish = new FinishSentenceRequest("El nino iba al patio.", 200L, 4_000L, 1, 0, 1, 0, false, UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> correction = pool.submit(call(() -> correctionService.process(
                    studentId, new ProcessCorrectionRequest("texto del alumno", responseId))));
            assertThat(aiEntered.await(15, TimeUnit.SECONDS)).as("correction reached the AI call").isTrue();
            // Mientras la IA "piensa" el telefono termina la oracion; ninguna transaccion retiene la fila.
            Future<?> finished = pool.submit(() -> studentTestService.finishSentence(studentId, attemptId, 1, finish));
            finished.get(15, TimeUnit.SECONDS);
            aiRelease.countDown();

            Outcome outcome = correction.get(15, TimeUnit.SECONDS);
            assertThat(outcome.error())
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Sentence is not open");
        } finally {
            pool.shutdownNow();
        }

        assertThat(sessionRepository.findByStudentIdOrderByCreatedAtDesc(studentId, PageRequest.of(0, 10)))
                .as("no session persisted for the rejected correction")
                .isEmpty();
        assertThat(sessionRepository.countByTestResponseId(responseId)).isZero();
        TestAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        assertThat(attempt.getModelVersion()).isNull();
        assertThat(attempt.getIncidentCount()).isZero();
        TestResponse response = responseRepository.findById(responseId).orElseThrow();
        assertThat(response.isFinished()).isTrue();
        assertThat(response.getFinalText()).isEqualTo("El nino iba al patio.");
        assertThat(AutoErrorDetail.incidents(response.getAutoErrorDetail())).isEmpty();
    }

    @Test
    void differingFirstModelVersionsRecordExactlyOneChangeIncident() throws Exception {
        when(aiCorrectionClient.correct("texto uno", studentId))
                .thenReturn(new AiCorrectionResponse(studentId, "texto uno", 10, List.of(), "m1"));
        when(aiCorrectionClient.correct("texto dos", studentId))
                .thenReturn(new AiCorrectionResponse(studentId, "texto dos", 10, List.of(), "m2"));

        List<Outcome> outcomes = race(List.of(
                () -> correctionService.process(studentId, new ProcessCorrectionRequest("texto uno", responseId)),
                () -> correctionService.process(studentId, new ProcessCorrectionRequest("texto dos", responseId))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        TestAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        assertThat(attempt.getModelVersion()).isIn("m1", "m2");
        assertThat(attempt.getIncidentCount()).isEqualTo(1);
        TestResponse response = responseRepository.findById(responseId).orElseThrow();
        assertThat(AutoErrorDetail.incidents(response.getAutoErrorDetail())).containsExactly("MODEL_VERSION_CHANGED");
        assertThat(sessionRepository.countByTestResponseId(responseId)).isEqualTo(2);
        assertThat(sessionRepository.findByTestResponseIdIn(List.of(responseId))).hasSize(2);

        // Al terminar la oracion dictada, el conteo automatico conserva la incidencia.
        studentTestService.finishSentence(studentId, attemptId, 1,
                new FinishSentenceRequest("El nino iba al patio.", 200L, 4_000L, 2, 1, 1, 0, false, UUID.randomUUID()));
        TestResponse finished = responseRepository.findById(responseId).orElseThrow();
        assertThat(finished.getAutoErrorCount()).isZero();
        assertThat(AutoErrorDetail.incidents(finished.getAutoErrorDetail())).containsExactly("MODEL_VERSION_CHANGED");
    }

    // ---------------------------------------------------------------- helpers

    /** Lanza cada llamada en su propio hilo, todas liberadas por un mismo latch. */
    private List<Outcome> race(List<Callable<CorrectionSessionResponse>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<CorrectionSessionResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call(call).call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Outcome> call(Callable<CorrectionSessionResponse> call) {
        return () -> {
            try {
                return new Outcome(call.call(), null);
            } catch (RuntimeException e) {
                return new Outcome(null, e);
            }
        };
    }

    private record Outcome(CorrectionSessionResponse response, RuntimeException error) {
    }
}
