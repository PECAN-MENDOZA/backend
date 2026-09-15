package com.mvp.backend.experiment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.research.domain.repository.StudyProtocolRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Races over H2 with the real transaction manager: two threads, each running the service in its
 * own transaction, released by a single latch. The pessimistic row lock makes the loser re-read the
 * committed state instead of overwriting the winner.
 */
@SpringBootTest
class StudentExperimentConcurrencyTests {

    private static final String INVALID = "Access code is invalid or unavailable";

    @Autowired
    private StudentExperimentService service;

    @Autowired
    private ResearcherRepository researcherRepository;

    @Autowired
    private ResearchStudyRepository studyRepository;

    @Autowired
    private StudyProtocolRepository protocolRepository;

    @Autowired
    private StudyParticipantRepository participantRepository;

    @Autowired
    private ExperimentRunRepository runRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    private UUID studentId;
    private UUID otherStudentId;
    private ResearchStudy study;
    private StudyProtocol protocol;

    @BeforeEach
    void setUp() {
        studentId = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash")).getId();
        otherStudentId = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash")).getId();
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
    void concurrentCompletionsKeepTheFirstResult() throws Exception {
        ExperimentRun run = issueRun(1, "ABCD2345");
        service.redeem(studentId, new RedeemAccessCodeRequest("ABCD2345"));
        service.start(studentId, run.getId());
        var first = new CompleteExperimentRequest("Texto del primer hilo", 40_000L, UUID.randomUUID(), "1.0.0");
        var second = new CompleteExperimentRequest("Texto del segundo hilo", 41_000L, UUID.randomUUID(), "1.0.0");

        List<Outcome<CompleteExperimentRequest>> outcomes = race(Map.of(
                first, () -> service.complete(studentId, run.getId(), first),
                second, () -> service.complete(studentId, run.getId(), second)));

        Outcome<CompleteExperimentRequest> winner = single(outcomes, true);
        Outcome<CompleteExperimentRequest> loser = single(outcomes, false);
        assertThat(winner.response().status()).isEqualTo("COMPLETED");
        assertThat(loser.error()).isInstanceOf(BusinessException.class).hasMessage("Run is not active");

        ExperimentRun stored = runRepository.findById(run.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ExperimentRunStatus.COMPLETED);
        assertThat(stored.getFinalText()).isEqualTo(winner.key().finalText());
        assertThat(stored.getDurationMs()).isEqualTo(winner.key().durationMs());
        assertThat(stored.getCompletionKey()).isEqualTo(winner.key().completionKey());
        assertThat(stored.getIncidentCount()).isZero();
    }

    @Test
    void twoStudentsCannotRedeemTheSameCode() throws Exception {
        String code = "EFGH6789";
        ExperimentRun run = issueRun(2, code);

        List<Outcome<UUID>> outcomes = race(Map.of(
                studentId, () -> service.redeem(studentId, new RedeemAccessCodeRequest(code)),
                otherStudentId, () -> service.redeem(otherStudentId, new RedeemAccessCodeRequest(code))));

        Outcome<UUID> winner = single(outcomes, true);
        Outcome<UUID> loser = single(outcomes, false);
        assertThat(winner.response().id()).isEqualTo(run.getId());
        assertThat(winner.response().status()).isEqualTo("PENDING");
        assertThat(loser.error()).isInstanceOf(BusinessException.class).hasMessage(INVALID);

        UUID boundStudent = transactionTemplate.execute(status ->
                runRepository.findById(run.getId()).orElseThrow().getParticipant().getStudent().getId());
        assertThat(boundStudent).isEqualTo(winner.key());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getRedeemedAt()).isNotNull();
        // The loser never bound anything, so it still has nothing to restore.
        assertThat(runRepository.findRestorableByStudentId(loser.key())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /** Runs every call on its own thread, all released by one latch; each service call is its own transaction. */
    private <K> List<Outcome<K>> race(Map<K, Supplier<ExperimentRunResponse>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Outcome<K>>> futures = new ArrayList<>();
            calls.forEach((key, call) -> futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return new Outcome<>(key, call.get(), null);
                } catch (RuntimeException e) {
                    return new Outcome<>(key, null, e);
                }
            })));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome<K>> outcomes = new ArrayList<>();
            for (Future<Outcome<K>> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <K> Outcome<K> single(List<Outcome<K>> outcomes, boolean succeeded) {
        List<Outcome<K>> matching = outcomes.stream().filter(o -> o.succeeded() == succeeded).toList();
        assertThat(matching).as(succeeded ? "successful calls" : "failed calls").hasSize(1);
        return matching.get(0);
    }

    private ExperimentRun issueRun(int participantNumber, String code) {
        return transactionTemplate.execute(status -> {
            StudyParticipant participant = participantRepository.save(new StudyParticipant(study, participantNumber));
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            return runRepository.save(new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), ExperimentCondition.ASSISTED,
                    AccessCode.hash(code), clock.instant().plus(Duration.ofMinutes(30)), clock.instant()));
        });
    }

    private record Outcome<K>(K key, ExperimentRunResponse response, RuntimeException error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
