package com.mvp.backend.sentencetest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.config.TestsProperties;
import com.mvp.backend.sentencetest.application.dto.ConditionMetrics;
import com.mvp.backend.sentencetest.application.dto.MetricInterval;
import com.mvp.backend.sentencetest.application.dto.PairedDelta;
import com.mvp.backend.sentencetest.application.dto.SentenceStat;
import com.mvp.backend.sentencetest.application.dto.TestResultsResponse;
import com.mvp.backend.sentencetest.application.service.ResearchTestService.Cohort;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.repository.TestAssignmentRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;

@ExtendWith(MockitoExtension.class)
class TestResultsServiceTests {

    private static final double EPS = 1e-9;

    @Mock private ResearchTestService research;
    @Mock private TestAssignmentRepository assignmentRepository;
    @Mock private TestAttemptRepository attemptRepository;

    private TestResultsService service;
    private UUID testId;
    private Cohort cohort;

    @BeforeEach
    void setUp() {
        service = new TestResultsService(research, assignmentRepository, attemptRepository, new TestsProperties(8),
                Clock.fixed(SyntheticCohort.NOW, ZoneOffset.UTC));
    }

    private TestResultsResponse resultsFor(Cohort cohort) {
        this.cohort = cohort;
        testId = cohort.test().getId();
        when(research.loadCohort(testId)).thenReturn(cohort);
        when(assignmentRepository.countByTestId(testId)).thenReturn(5L);
        when(attemptRepository.countByTestIdAndStatus(testId, AttemptStatus.CANCELLED)).thenReturn(1L);
        when(attemptRepository.countByTestIdAndStatus(testId, AttemptStatus.IN_PROGRESS)).thenReturn(2L);
        return service.results(testId);
    }

    @Test
    void headerSampleAndFlags() {
        TestResultsResponse results = resultsFor(SyntheticCohort.build(false));

        assertThat(results.testId()).isEqualTo(testId);
        assertThat(results.code()).isEqualTo("PRUEBA-01");
        assertThat(results.title()).isEqualTo("Prueba piloto");
        assertThat(results.status()).isEqualTo("ACTIVE");
        assertThat(results.computedAt()).isEqualTo(SyntheticCohort.NOW);
        assertThat(results.sample().assigned()).isEqualTo(5);
        assertThat(results.sample().completed()).isEqualTo(3);
        assertThat(results.sample().excluded()).isZero();
        assertThat(results.sample().cancelled()).isEqualTo(1);
        assertThat(results.sample().inProgress()).isEqualTo(2);
        assertThat(results.sample().unannotatedFree()).isEqualTo(1);
        assertThat(results.incomplete()).isTrue();
        assertThat(results.sampleInsufficient()).isTrue();
        assertThat(results.minSample()).isEqualTo(8);
        assertThat(results.designManual()).isTrue();
        assertThat(results.datasetSha256()).matches("[0-9a-f]{64}");
        assertThat(results.datasetSha256()).isEqualTo(TestExportCsv.sha256Hex(TestExportCsv.build(cohort)));
        assertThat(results.provenance().modelVersions()).containsExactly("beto-v3", "beto-v4");
        assertThat(results.provenance().appVersions()).containsExactly("app-1", "app-2");
        assertThat(results.provenance().backendVersions()).containsExactly("backend-1");
    }

    @Test
    void errorsPer100WordsPerConditionAreComputedByHand() {
        TestResultsResponse results = resultsFor(SyntheticCohort.build(false));

        ConditionMetrics assisted = results.conditions().get(Assistance.ASSISTED);
        ConditionMetrics unassisted = results.conditions().get(Assistance.UNASSISTED);
        assertThat(assisted.participants()).isEqualTo(3);
        assertThat(unassisted.participants()).isEqualTo(3);

        // Con ayuda: A 200/7 (2 errores en 7 palabras), B 0/4, C 200/3 (la libre sin anotar no cuenta).
        MetricInterval errorsAssisted = assisted.errorsPer100Words();
        assertThat(errorsAssisted.n()).isEqualTo(3);
        assertThat(errorsAssisted.mean()).isCloseTo((200.0 / 7 + 0 + 200.0 / 3) / 3, within(EPS));
        assertThat(errorsAssisted.lower()).isNotNull().isLessThanOrEqualTo(errorsAssisted.mean());
        assertThat(errorsAssisted.upper()).isNotNull().isGreaterThanOrEqualTo(errorsAssisted.mean());
        // Sin ayuda: A 2/8, B 1/4 (la omitida no cuenta), C 1/6.
        MetricInterval errorsUnassisted = unassisted.errorsPer100Words();
        assertThat(errorsUnassisted.n()).isEqualTo(3);
        assertThat(errorsUnassisted.mean()).isCloseTo((25.0 + 25.0 + 100.0 / 6) / 3, within(EPS));

        // El intervalo es el mismo bootstrap determinista sobre los valores por alumno.
        MetricInterval expected = Bootstrap.meanInterval(new double[] {200.0 / 7, 0, 200.0 / 3});
        assertThat(errorsAssisted.lower()).isCloseTo(expected.lower(), within(EPS));
        assertThat(errorsAssisted.upper()).isCloseTo(expected.upper(), within(EPS));
    }

    @Test
    void wordsPerMinuteAndAcceptanceRate() {
        TestResultsResponse results = resultsFor(SyntheticCohort.build(false));

        ConditionMetrics assisted = results.conditions().get(Assistance.ASSISTED);
        ConditionMetrics unassisted = results.conditions().get(Assistance.UNASSISTED);
        // PPM con ayuda: A 7 palabras / 9000 ms, B 4 / 3000, C 7 / 11000.
        double a = 7 / (9000 / 60000.0);
        double b = 4 / (3000 / 60000.0);
        double c = 7 / (11000 / 60000.0);
        assertThat(assisted.wordsPerMinute().n()).isEqualTo(3);
        assertThat(assisted.wordsPerMinute().mean()).isCloseTo((a + b + c) / 3, within(EPS));
        assertThat(unassisted.wordsPerMinute().mean()).isCloseTo((40.0 + 120.0 + 30.0) / 3, within(EPS));

        // Aceptacion agregada solo con ayuda: 6 aceptadas de 9 ofrecidas.
        assertThat(assisted.acceptanceRate()).isNotNull();
        assertThat(assisted.acceptanceRate().accepted()).isEqualTo(6);
        assertThat(assisted.acceptanceRate().offered()).isEqualTo(9);
        assertThat(assisted.acceptanceRate().ratePct()).isCloseTo(600.0 / 9, within(EPS));
        assertThat(assisted.acceptanceRate().wilsonLower()).isBetween(0.0, 600.0 / 9);
        assertThat(assisted.acceptanceRate().wilsonUpper()).isBetween(600.0 / 9, 100.0);
        assertThat(unassisted.acceptanceRate()).isNull();
    }

    @Test
    void pairedDeltasUseStudentsWithBothConditions() {
        TestResultsResponse results = resultsFor(SyntheticCohort.build(false));

        PairedDelta errors = results.paired().errorsPer100Words();
        assertThat(errors.n()).isEqualTo(3);
        assertThat(errors.meanAssisted()).isCloseTo((200.0 / 7 + 200.0 / 3) / 3, within(EPS));
        assertThat(errors.meanUnassisted()).isCloseTo((50.0 + 100.0 / 6) / 3, within(EPS));
        assertThat(errors.meanDelta()).isCloseTo(errors.meanAssisted() - errors.meanUnassisted(), within(EPS));
        MetricInterval expected = Bootstrap.meanInterval(new double[] {200.0 / 7 - 25, 0 - 25, 200.0 / 3 - 100.0 / 6});
        assertThat(errors.bootstrapLower()).isCloseTo(expected.lower(), within(EPS));
        assertThat(errors.bootstrapUpper()).isCloseTo(expected.upper(), within(EPS));
        assertThat(errors.tLower()).isNotNull().isLessThan(errors.meanDelta());
        assertThat(errors.tUpper()).isNotNull().isGreaterThan(errors.meanDelta());
        assertThat(errors.t()).isNotNull();
        assertThat(errors.p()).isNotNull().isBetween(0.0, 1.0);
        assertThat(errors.dz()).isNotNull();

        PairedDelta ppm = results.paired().wordsPerMinute();
        assertThat(ppm.n()).isEqualTo(3);
        assertThat(ppm.meanUnassisted()).isCloseTo((40.0 + 120.0 + 30.0) / 3, within(EPS));
    }

    @Test
    void perSentenceStats() {
        TestResultsResponse results = resultsFor(SyntheticCohort.build(false));

        assertThat(results.sentences()).hasSize(4);
        SentenceStat first = results.sentences().get(0);
        assertThat(first.position()).isEqualTo(1);
        assertThat(first.kind()).isEqualTo("DICTATED");
        assertThat(first.assistance()).isEqualTo("ASSISTED");
        assertThat(first.n()).isEqualTo(3);
        assertThat(first.meanErrors()).isCloseTo(1.0, within(EPS));
        assertThat(first.meanDurationFirstKeyMs()).isCloseTo((3000 + 2000 + 6000) / 3.0, within(EPS));
        assertThat(first.skippedCount()).isZero();

        // Oracion 3: C no esta anotada, la media de errores solo usa A (1) y B (0).
        SentenceStat third = results.sentences().get(2);
        assertThat(third.n()).isEqualTo(3);
        assertThat(third.meanErrors()).isCloseTo(0.5, within(EPS));

        // Oracion 4: B la omitio; media de errores sobre A (0) y C (1); duracion sobre A y C.
        SentenceStat fourth = results.sentences().get(3);
        assertThat(fourth.n()).isEqualTo(3);
        assertThat(fourth.skippedCount()).isEqualTo(1);
        assertThat(fourth.meanErrors()).isCloseTo(0.5, within(EPS));
        assertThat(fourth.meanDurationFirstKeyMs()).isCloseTo(6000.0, within(EPS));
    }

    @Test
    void excludedAttemptsAreCountedButNotAnalyzed() {
        TestResultsResponse results = resultsFor(SyntheticCohort.build(true));

        assertThat(results.sample().completed()).isEqualTo(4);
        assertThat(results.sample().excluded()).isEqualTo(1);
        assertThat(results.paired().errorsPer100Words().n()).isEqualTo(3);
        assertThat(results.sentences().get(0).n()).isEqualTo(3);
        assertThat(results.conditions().get(Assistance.ASSISTED).participants()).isEqualTo(3);
        // La huella del CSV cambia porque el CSV si incluye al excluido.
        assertThat(results.datasetSha256()).isNotEqualTo(resultsFor(SyntheticCohort.build(false)).datasetSha256());
    }

    @Test
    void completeWhenAllFreeSentencesAreAnnotated() {
        Cohort cohort = SyntheticCohort.build(false);
        cohort.responsesByAttempt().values().stream()
                .flatMap(java.util.List::stream)
                .filter(r -> r.effectiveErrorCount() == null && !r.isSkipped())
                .forEach(r -> r.annotate(3, SyntheticCohort.RESEARCHER, SyntheticCohort.NOW));
        TestResultsResponse results = resultsFor(cohort);

        assertThat(results.incomplete()).isFalse();
        assertThat(results.sample().unannotatedFree()).isZero();
        // C con ayuda pasa a (2 + 3) errores en 3 + 4 palabras.
        assertThat(results.conditions().get(Assistance.ASSISTED).errorsPer100Words().mean())
                .isCloseTo((200.0 / 7 + 0 + 500.0 / 7) / 3, within(EPS));
    }
}
