package com.mvp.backend.sentencetest.application.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.config.TestsProperties;
import com.mvp.backend.sentencetest.application.dto.ConditionMetrics;
import com.mvp.backend.sentencetest.application.dto.MetricInterval;
import com.mvp.backend.sentencetest.application.dto.PairedDelta;
import com.mvp.backend.sentencetest.application.dto.SentenceStat;
import com.mvp.backend.sentencetest.application.dto.TestResultsResponse;
import com.mvp.backend.sentencetest.application.service.ResearchTestService.Cohort;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.TestAssignmentRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;

/**
 * Resultados por condicion (errores/100 palabras, PPM, aceptacion) con IC bootstrap determinista,
 * diferencia pareada con-sin por alumno, resumen por oracion y exportacion CSV con su huella SHA-256.
 * Solo se analizan intentos completados no excluidos; el CSV incluye tambien los excluidos.
 */
@Service
public class TestResultsService {

    /** CSV listo para descargar junto con su huella. */
    public record CsvExport(String code, byte[] bytes, String sha256) {
    }

    /** Acumuladores de un alumno en una condicion. */
    private static final class StudentCondition {
        long errors;
        long errorWords;
        long ppmWords;
        long durationMs;

        Double errorsPer100Words() {
            return errorWords == 0 ? null : 100.0 * errors / errorWords;
        }

        Double wordsPerMinute() {
            return durationMs == 0 ? null : ppmWords / (durationMs / 60_000.0);
        }
    }

    private final ResearchTestService research;
    private final TestAssignmentRepository assignmentRepository;
    private final TestAttemptRepository attemptRepository;
    private final TestsProperties properties;
    private final Clock clock;

    public TestResultsService(ResearchTestService research, TestAssignmentRepository assignmentRepository,
            TestAttemptRepository attemptRepository, TestsProperties properties, Clock clock) {
        this.research = research;
        this.assignmentRepository = assignmentRepository;
        this.attemptRepository = attemptRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // La transaccion envuelve loadCohort para poder recorrer las asociaciones perezosas (oracion, alumno).
    @Transactional(readOnly = true)
    public TestResultsResponse results(UUID testId) {
        Cohort cohort = research.loadCohort(testId);
        SentenceTest test = cohort.test();
        List<TestAttempt> analyzed = cohort.completedNotExcluded();

        // Alumno (por username, orden estable que replica el script Python) -> condicion -> acumuladores.
        Map<String, Map<Assistance, StudentCondition>> perStudent = new TreeMap<>();
        Map<Assistance, long[]> suggestions = new EnumMap<>(Assistance.class);
        Map<Assistance, TreeSet<String>> participants = new EnumMap<>(Assistance.class);
        for (Assistance assistance : Assistance.values()) {
            suggestions.put(assistance, new long[2]);
            participants.put(assistance, new TreeSet<>());
        }
        int unannotatedFree = 0;
        for (TestAttempt attempt : analyzed) {
            String username = cohort.usernameByStudent().get(attempt.getStudent().getId());
            for (TestResponse response : responses(cohort, attempt)) {
                TestSentence sentence = response.getSentence();
                Assistance assistance = sentence.getAssistance();
                if (!response.isFinished()) {
                    continue;
                }
                // Una oracion omitida no contribuye a ninguna metrica, incluidos los contadores de sugerencias.
                if (response.isSkipped()) {
                    continue;
                }
                long[] counters = suggestions.get(assistance);
                counters[0] += response.getSuggestionsOffered();
                counters[1] += response.getSuggestionsAccepted();
                participants.get(assistance).add(username);
                StudentCondition acc = perStudent.computeIfAbsent(username, u -> new EnumMap<>(Assistance.class))
                        .computeIfAbsent(assistance, a -> new StudentCondition());
                int words = TestExportCsv.wordCount(response);
                Integer errors = response.effectiveErrorCount();
                if (errors == null && sentence.getKind() == SentenceKind.FREE) {
                    unannotatedFree++;
                }
                if (words > 0 && errors != null) {
                    acc.errors += errors;
                    acc.errorWords += words;
                }
                Long duration = response.getDurationFromFirstKeyMs();
                if (duration != null && duration > 0) {
                    acc.ppmWords += words;
                    acc.durationMs += duration;
                }
            }
        }

        Map<Assistance, ConditionMetrics> conditions = new EnumMap<>(Assistance.class);
        for (Assistance assistance : Assistance.values()) {
            List<Double> errors = values(perStudent, assistance, StudentCondition::errorsPer100Words);
            List<Double> ppm = values(perStudent, assistance, StudentCondition::wordsPerMinute);
            ConditionMetrics.AcceptanceRate acceptance = null;
            if (assistance == Assistance.ASSISTED) {
                long offered = suggestions.get(assistance)[0];
                long accepted = suggestions.get(assistance)[1];
                Stats.WilsonInterval wilson = Stats.wilson(accepted, offered);
                acceptance = new ConditionMetrics.AcceptanceRate(accepted, offered,
                        offered == 0 ? null : 100.0 * accepted / offered, wilson.lower(), wilson.upper());
            }
            conditions.put(assistance, new ConditionMetrics(participants.get(assistance).size(),
                    Bootstrap.meanInterval(toArray(errors)), Bootstrap.meanInterval(toArray(ppm)), acceptance));
        }

        TestResultsResponse.Paired paired = new TestResultsResponse.Paired(
                paired(perStudent, StudentCondition::errorsPer100Words),
                paired(perStudent, StudentCondition::wordsPerMinute));

        List<SentenceStat> sentences = cohort.sentences().stream()
                .map(sentence -> sentenceStat(sentence, cohort, analyzed))
                .toList();

        int completedAll = cohort.completedAll().size();
        TestResultsResponse.Sample sample = new TestResultsResponse.Sample(
                (int) assignmentRepository.countByTestId(testId),
                completedAll,
                completedAll - analyzed.size(),
                (int) attemptRepository.countByTestIdAndStatus(testId, AttemptStatus.CANCELLED),
                (int) attemptRepository.countByTestIdAndStatus(testId, AttemptStatus.IN_PROGRESS),
                unannotatedFree);

        byte[] csv = TestExportCsv.build(cohort);
        return new TestResultsResponse(test.getId(), test.getCode(), test.getTitle(), test.getStatus().name(),
                clock.instant(), sample, unannotatedFree > 0, analyzed.size() < properties.minSample(),
                properties.minSample(), true, conditions, paired, sentences, provenance(analyzed),
                TestExportCsv.sha256Hex(csv));
    }

    @Transactional(readOnly = true)
    public CsvExport exportCsv(UUID testId) {
        Cohort cohort = research.loadCohort(testId);
        byte[] csv = TestExportCsv.build(cohort);
        return new CsvExport(cohort.test().getCode(), csv, TestExportCsv.sha256Hex(csv));
    }

    // --- helpers ---

    private static List<TestResponse> responses(Cohort cohort, TestAttempt attempt) {
        return cohort.responsesByAttempt().getOrDefault(attempt.getId(), List.of());
    }

    private static List<Double> values(Map<String, Map<Assistance, StudentCondition>> perStudent, Assistance assistance,
            Function<StudentCondition, Double> metric) {
        List<Double> values = new ArrayList<>();
        for (Map<Assistance, StudentCondition> byCondition : perStudent.values()) {
            StudentCondition acc = byCondition.get(assistance);
            Double value = acc == null ? null : metric.apply(acc);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** Alumnos con valor en ambas condiciones; bootstrap sobre las diferencias y resumen t pareado. */
    private static PairedDelta paired(Map<String, Map<Assistance, StudentCondition>> perStudent,
            Function<StudentCondition, Double> metric) {
        List<Double> assisted = new ArrayList<>();
        List<Double> unassisted = new ArrayList<>();
        for (Map<Assistance, StudentCondition> byCondition : perStudent.values()) {
            StudentCondition with = byCondition.get(Assistance.ASSISTED);
            StudentCondition without = byCondition.get(Assistance.UNASSISTED);
            Double a = with == null ? null : metric.apply(with);
            Double u = without == null ? null : metric.apply(without);
            if (a != null && u != null) {
                assisted.add(a);
                unassisted.add(u);
            }
        }
        if (assisted.isEmpty()) {
            return PairedDelta.EMPTY;
        }
        double[] a = toArray(assisted);
        double[] u = toArray(unassisted);
        double[] deltas = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            deltas[i] = a[i] - u[i];
        }
        MetricInterval bootstrap = Bootstrap.meanInterval(deltas);
        Stats.PairedSummary summary = Stats.pairedSummary(a, u);
        return new PairedDelta(summary.n(), summary.meanAssisted(), summary.meanUnassisted(), summary.meanDelta(),
                bootstrap.lower(), bootstrap.upper(), summary.lower(), summary.upper(), summary.t(), summary.p(),
                summary.dz());
    }

    private static SentenceStat sentenceStat(TestSentence sentence, Cohort cohort, List<TestAttempt> analyzed) {
        int n = 0;
        int skipped = 0;
        long errors = 0;
        int errorCount = 0;
        long duration = 0;
        int durationCount = 0;
        for (TestAttempt attempt : analyzed) {
            for (TestResponse response : responses(cohort, attempt)) {
                if (response.getPosition() != sentence.getPosition() || !response.isFinished()) {
                    continue;
                }
                n++;
                if (response.isSkipped()) {
                    skipped++;
                    continue;
                }
                Integer known = response.effectiveErrorCount();
                if (known != null) {
                    errors += known;
                    errorCount++;
                }
                Long ms = response.getDurationFromFirstKeyMs();
                if (ms != null && ms > 0) {
                    duration += ms;
                    durationCount++;
                }
            }
        }
        return new SentenceStat(sentence.getPosition(), sentence.getKind().name(), sentence.getAssistance().name(), n,
                errorCount == 0 ? null : (double) errors / errorCount,
                durationCount == 0 ? null : (double) duration / durationCount,
                skipped);
    }

    private static TestResultsResponse.Provenance provenance(List<TestAttempt> analyzed) {
        return new TestResultsResponse.Provenance(
                distinctSorted(analyzed, TestAttempt::getModelVersion),
                distinctSorted(analyzed, TestAttempt::getAppVersion),
                distinctSorted(analyzed, TestAttempt::getBackendVersion));
    }

    private static List<String> distinctSorted(List<TestAttempt> attempts, Function<TestAttempt, String> field) {
        return attempts.stream().map(field).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static double[] toArray(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
