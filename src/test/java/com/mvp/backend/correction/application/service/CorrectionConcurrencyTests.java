package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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
import com.mvp.backend.experiment.application.dto.CompleteExperimentRequest;
import com.mvp.backend.experiment.application.service.StudentExperimentService;
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
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.research.domain.repository.StudyProtocolRepository;
import com.mvp.backend.shared.exception.AiServiceException;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Races over H2 with the real transaction manager (same style as StudentExperimentConcurrencyTests):
 * the IA call runs outside any transaction, so the run is only mutated under its row lock in the
 * write phase, and every incident is counted even when two corrections fail at the same time.
 */
@SpringBootTest
class CorrectionConcurrencyTests {

    @Autowired
    private CorrectionService correctionService;

    @Autowired
    private StudentExperimentService experimentService;

    @Autowired
    private CorrectionSessionRepository sessionRepository;

    @Autowired
    private ExperimentRunRepository runRepository;

    @Autowired
    private ResearcherRepository researcherRepository;

    @Autowired
    private ResearchStudyRepository studyRepository;

    @Autowired
    private StudyProtocolRepository protocolRepository;

    @Autowired
    private StudyParticipantRepository participantRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @MockitoBean
    private AiCorrectionClient aiCorrectionClient;

    private Student student;
    private ResearchStudy study;
    private StudyProtocol protocol;

    @BeforeEach
    void setUp() {
        student = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash"));
        Researcher researcher = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash"));
        study = new ResearchStudy("EXP-" + UUID.randomUUID().toString().substring(0, 8), "Carreras", researcher);
        study.activate();
        study = studyRepository.save(study);
        protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Cuenta tu fin de semana");
        protocol.addTask(TaskVariant.TASK_B, "Describe tu escuela");
        protocol.activate();
        protocol = protocolRepository.save(protocol);
    }

    @Test
    void correctionArrivingAfterCompletionIsRejectedAndDoesNotTouchTheRun() throws Exception {
        UUID runId = activeRun(1);
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch aiRelease = new CountDownLatch(1);
        when(aiCorrectionClient.correct(anyString(), any())).thenAnswer(invocation -> {
            aiEntered.countDown();
            assertThat(aiRelease.await(15, TimeUnit.SECONDS)).isTrue();
            return new AiCorrectionResponse(student.getId(), "texto corregido", 10, List.of(), "beto-lora-1.2");
        });
        var completion = new CompleteExperimentRequest("Texto final del alumno", 40_000L, UUID.randomUUID(), "1.0.0");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> correction = pool.submit(call(() -> correctionService.process(
                    student.getId(), new ProcessCorrectionRequest("texto del alumno", runId))));
            assertThat(aiEntered.await(15, TimeUnit.SECONDS)).as("correction reached the AI call").isTrue();
            // While the AI is "thinking" the phone completes the run; no transaction is holding the row.
            Future<?> completed = pool.submit(() -> experimentService.complete(student.getId(), runId, completion));
            completed.get(15, TimeUnit.SECONDS);
            aiRelease.countDown();

            Outcome outcome = correction.get(15, TimeUnit.SECONDS);
            assertThat(outcome.error())
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Experiment run is no longer active");
        } finally {
            pool.shutdownNow();
        }

        assertThat(sessionRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(), PageRequest.of(0, 10)))
                .as("no session persisted for the rejected correction")
                .isEmpty();
        ExperimentRun stored = runRepository.findById(runId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
        assertThat(stored.getFinalText()).isEqualTo(completion.finalText());
        assertThat(stored.getCompletionKey()).isEqualTo(completion.completionKey());
        assertThat(stored.getModelVersion()).isNull();
        assertThat(stored.getIncidentCount()).isZero();
    }

    @Test
    void simultaneousAiFailuresBothCountAsIncidents() throws Exception {
        UUID runId = activeRun(2);
        when(aiCorrectionClient.correct(anyString(), any())).thenThrow(new AiServiceException("unavailable", null));

        List<Outcome> outcomes = race(List.of(
                () -> correctionService.process(student.getId(), new ProcessCorrectionRequest("primer texto", runId)),
                () -> correctionService.process(student.getId(), new ProcessCorrectionRequest("segundo texto", runId))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isInstanceOf(AiServiceException.class));
        ExperimentRun stored = runRepository.findById(runId).orElseThrow();
        assertThat(stored.getIncidentCount()).isEqualTo(2);
        assertThat(stored.getFailureReason()).isEqualTo("AI_REQUEST_FAILED");
        assertThat(stored.isActive()).isTrue();
        assertThat(sessionRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(), PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void differingFirstModelVersionsRecordExactlyOneChangeIncident() throws Exception {
        UUID runId = activeRun(3);
        when(aiCorrectionClient.correct("texto uno", student.getId()))
                .thenReturn(new AiCorrectionResponse(student.getId(), "texto uno", 10, List.of(), "m1"));
        when(aiCorrectionClient.correct("texto dos", student.getId()))
                .thenReturn(new AiCorrectionResponse(student.getId(), "texto dos", 10, List.of(), "m2"));

        List<Outcome> outcomes = race(List.of(
                () -> correctionService.process(student.getId(), new ProcessCorrectionRequest("texto uno", runId)),
                () -> correctionService.process(student.getId(), new ProcessCorrectionRequest("texto dos", runId))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        ExperimentRun stored = runRepository.findById(runId).orElseThrow();
        assertThat(stored.getModelVersion()).isIn("m1", "m2");
        assertThat(stored.getIncidentCount()).isEqualTo(1);
        assertThat(stored.getFailureReason()).isEqualTo("MODEL_VERSION_CHANGED");
        assertThat(stored.isActive()).isTrue();
        assertThat(sessionRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(), PageRequest.of(0, 10)))
                .hasSize(2);
    }

    // ---------------------------------------------------------------- helpers

    /** Runs every call on its own thread, all released by one latch. */
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

    /** ACTIVE assisted run of {@code student}, committed before the test body runs. */
    private UUID activeRun(int participantNumber) {
        return transactionTemplate.execute(status -> {
            StudyParticipant participant = new StudyParticipant(study, participantNumber);
            participant.linkStudent(student);
            participant = participantRepository.save(participant);
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            ExperimentRun run = new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), ExperimentCondition.ASSISTED,
                    AccessCode.hash("ABCD2345"), clock.instant().plus(Duration.ofMinutes(30)), clock.instant());
            run.redeem(clock.instant());
            run.start(clock.instant());
            return runRepository.save(run).getId();
        });
    }

    private record Outcome(CorrectionSessionResponse response, RuntimeException error) {
    }
}
