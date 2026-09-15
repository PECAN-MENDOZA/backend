package com.mvp.backend.research.application.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.application.dto.AnnotationBatchResponse;
import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationItem;
import com.mvp.backend.research.domain.model.AnnotationKind;
import com.mvp.backend.research.domain.model.AnnotationSlot;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.TaskVariant;
import com.mvp.backend.research.domain.repository.AnnotationImportRepository;
import com.mvp.backend.research.domain.repository.AnnotationItemRepository;
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.research.domain.repository.StudyProtocolRepository;
import com.mvp.backend.shared.exception.BusinessException;

/**
 * Races over H2 with the real transaction manager (same style as CorrectionConcurrencyTests): two
 * imports released by one latch, each in its own transaction. The batch row lock serialises them so
 * the loser re-reads the committed slot state instead of working on a stale copy.
 */
@SpringBootTest
class ResearchAnnotationConcurrencyTests {

    @Autowired
    private ResearchAnnotationService service;

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
    private AnnotationItemRepository itemRepository;

    @Autowired
    private AnnotationImportRepository importRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    private UUID researcherId;
    private ResearchStudy study;
    private UUID batchId;
    private List<String> codes;

    @BeforeEach
    void setUp() {
        Researcher researcher = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash"));
        researcherId = researcher.getId();
        study = new ResearchStudy("EXP-" + UUID.randomUUID().toString().substring(0, 8), "Concurrencia", researcher);
        study.activate();
        study = studyRepository.save(study);
        StudyProtocol protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Cuenta tu fin de semana");
        protocol.addTask(TaskVariant.TASK_B, "Describe tu escuela");
        protocol.activate();
        StudyProtocol saved = protocolRepository.save(protocol);
        completedRun(saved, 1, "uno dos tres");
        completedRun(saved, 2, "cuatro cinco seis");
        completedRun(saved, 3, "siete ocho nueve");
        batchId = service.createBatch(researcherId, study.getId(), AnnotationKind.ORTHOGRAPHY).id();
        codes = itemRepository.findByBatchIdOrderByPositionAsc(batchId).stream().map(AnnotationItem::getSampleCode).toList();
    }

    @Test
    void concurrentImportsToDifferentSlotsKeepBothScoreColumns() throws Exception {
        List<Outcome> outcomes = race(List.of(
                () -> importScores(AnnotationSlot.RATER_1, "Ana", csv(1, 0, 1)),
                () -> importScores(AnnotationSlot.RATER_2, "Beto", csv(0, 0, 1))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        List<AnnotationItem> items = itemRepository.findByBatchIdOrderByPositionAsc(batchId);
        assertThat(items).extracting(AnnotationItem::getRater1Score).containsExactly(1, 0, 1);
        assertThat(items).extracting(AnnotationItem::getRater2Score).containsExactly(0, 0, 1);
        List<AnnotationImport> imports = importRepository.findByBatchIdOrderByVersionAsc(batchId);
        assertThat(imports).hasSize(2).allMatch(AnnotationImport::isCurrent);
        assertThat(imports).extracting(AnnotationImport::getSlot)
                .containsExactlyInAnyOrder(AnnotationSlot.RATER_1, AnnotationSlot.RATER_2);
        AnnotationBatchResponse summary = service.getBatch(researcherId, study.getId(), batchId);
        assertThat(summary.completedSlots()).containsExactly(AnnotationSlot.RATER_1, AnnotationSlot.RATER_2);
        assertThat(summary.agreement().complete()).isTrue();
    }

    @Test
    void concurrentImportsWithTheSameRaterInBothSlotsAdmitExactlyOne() throws Exception {
        List<Outcome> outcomes = race(List.of(
                () -> importScores(AnnotationSlot.RATER_1, "Ana", csv(1, 0, 1)),
                () -> importScores(AnnotationSlot.RATER_2, "ana", csv(0, 0, 1))));

        assertThat(outcomes).filteredOn(outcome -> outcome.error() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.error() != null).singleElement()
                .satisfies(outcome -> assertThat(outcome.error())
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("RATER_1 and RATER_2 must be distinct raters"));
        assertThat(importRepository.findByBatchIdOrderByVersionAsc(batchId)).hasSize(1);
    }

    @Test
    void concurrentReimportsOfOneSlotLeaveExactlyOneCurrent() throws Exception {
        importScores(AnnotationSlot.RATER_1, "Ana", csv(1, 1, 1));

        List<Outcome> outcomes = race(List.of(
                () -> importScores(AnnotationSlot.RATER_1, "Ana", csv(2, 0, 0)),
                () -> importScores(AnnotationSlot.RATER_1, "Ana", csv(0, 2, 0))));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        List<AnnotationImport> imports = importRepository.findByBatchIdOrderByVersionAsc(batchId);
        assertThat(imports).hasSize(3);
        assertThat(imports).extracting(AnnotationImport::getVersion).containsExactly(1, 2, 3);
        assertThat(imports).filteredOn(AnnotationImport::isCurrent).hasSize(1);
        AnnotationImport current = imports.stream().filter(AnnotationImport::isCurrent).findFirst().orElseThrow();
        assertThat(current.getVersion()).isEqualTo(3);
        // The item scores are the ones of the import that won the row lock last (the current one).
        List<AnnotationItem> items = itemRepository.findByBatchIdOrderByPositionAsc(batchId);
        List<Integer> stored = items.stream().map(AnnotationItem::getRater1Score).toList();
        assertThat(current.getContent()).isEqualTo(csv(stored.get(0), stored.get(1), stored.get(2)));
    }

    // ---------------------------------------------------------------- helpers

    private AnnotationBatchResponse importScores(AnnotationSlot slot, String rater, String csv) {
        return service.importScores(researcherId, study.getId(), batchId, slot, rater, csv.getBytes(UTF_8));
    }

    private String csv(int first, int second, int third) {
        return "sample_code,score\n" + codes.get(0) + "," + first + "\n" + codes.get(1) + "," + second + "\n"
                + codes.get(2) + "," + third + "\n";
    }

    /** Runs every call on its own thread, all released by one latch. */
    private List<Outcome> race(List<Callable<AnnotationBatchResponse>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<AnnotationBatchResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome(call.call(), null);
                    } catch (RuntimeException e) {
                        return new Outcome(null, e);
                    }
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

    private void completedRun(StudyProtocol protocol, int participantNumber, String finalText) {
        transactionTemplate.executeWithoutResult(status -> {
            StudyParticipant participant = participantRepository.save(new StudyParticipant(study, participantNumber));
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            ExperimentRun run = new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), ExperimentCondition.ASSISTED,
                    AccessCode.hash("ABCD234" + participantNumber), clock.instant().plus(Duration.ofMinutes(30)),
                    clock.instant());
            run.redeem(clock.instant());
            run.start(clock.instant());
            run.complete(finalText, 60_000, UUID.randomUUID(), clock.instant());
            runRepository.save(run);
        });
    }

    private record Outcome(AnnotationBatchResponse response, RuntimeException error) {
    }
}
