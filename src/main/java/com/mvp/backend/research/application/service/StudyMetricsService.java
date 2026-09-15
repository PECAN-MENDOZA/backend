package com.mvp.backend.research.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.inference.TTest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.config.ResearchProperties;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.application.dto.AnnotationCsvFile;
import com.mvp.backend.research.application.dto.PairValue;
import com.mvp.backend.research.application.dto.PairedSummary;
import com.mvp.backend.research.application.dto.RunMetrics;
import com.mvp.backend.research.application.dto.StudyResultsResponse;
import com.mvp.backend.research.application.dto.StudyResultsResponse.AnnotationStatus;
import com.mvp.backend.research.application.dto.StudyResultsResponse.Dataset;
import com.mvp.backend.research.application.dto.StudyResultsResponse.ParticipantResult;
import com.mvp.backend.research.application.dto.StudyResultsResponse.PeoResult;
import com.mvp.backend.research.application.dto.StudyResultsResponse.PpmResult;
import com.mvp.backend.research.application.dto.StudyResultsResponse.Provenance;
import com.mvp.backend.research.application.dto.StudyResultsResponse.Sample;
import com.mvp.backend.research.application.dto.StudyResultsResponse.TasResult;
import com.mvp.backend.research.domain.model.AnnotationBatch;
import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationItem;
import com.mvp.backend.research.domain.model.AnnotationKind;
import com.mvp.backend.research.domain.model.AnnotationSlot;
import com.mvp.backend.research.domain.model.ResearchAuditEvent;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.repository.AnnotationBatchRepository;
import com.mvp.backend.research.domain.repository.AnnotationImportRepository;
import com.mvp.backend.research.domain.repository.AnnotationItemRepository;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.shared.exception.NotFoundException;

/**
 * Resultados del estudio (spec §9.4 y §11; criterios de exito §3-§5).
 *
 * <p><b>Muestra.</b> La cohorte base son los participantes con un "par completo": al menos una ejecucion
 * COMPLETED no excluida en cada condicion, sin filtro por palabras. Cada metrica usa su propio conjunto
 * analizable dentro de la cohorte: PPM todas las ejecuciones incluidas (sin palabras contables vale 0); PEO solo
 * los participantes con palabras contables en ambas condiciones; TAS las ejecuciones ASSISTED con al menos una
 * sugerencia evaluada. Varias ejecuciones de un mismo participante y condicion se promedian a un valor antes de
 * la inferencia emparejada; la unidad de analisis es el participante. Los conteos informados forman particiones
 * (ver {@link Sample}).
 *
 * <p><b>Formulas.</b> {@code PEO = errores ortograficos adjudicados / palabras del texto final x 100};
 * {@code PPM = palabras / (duracion_ms / 60000)}; {@code TAS = sugerencias adjudicadas con 0 / evaluadas
 * x 100}; {@code TAS aceptada = perjudiciales aceptadas / aceptadas x 100} (aceptacion congelada en el lote
 * semantico, nunca el estado vivo de la sesion). Diferencia emparejada {@code ASSISTED - UNASSISTED}, IC 95 %
 * con distribucion t, valor p bilateral (prueba t emparejada) y d_z de Cohen = media(delta) / desviacion(delta).
 * Para TAS el intervalo inferencial es el de Wilson (z = 1.959964) sobre la proporcion agregada
 * {@code perjudiciales / evaluadas}; el criterio {@code upperCiBelowLimit} compara su limite superior con el
 * limite configurado. El intervalo t sobre el valor por participante se informa acotado a [0, 100] y es solo
 * descriptivo.
 *
 * <p><b>Tokenizacion documentada</b> ({@link #WORD}): una palabra es una secuencia de letras o digitos
 * Unicode, con apostrofes o guiones internos ("l'amour", "re-hacer" cuentan una vez); la puntuacion y los
 * simbolos no cuentan. La misma regla acota el puntaje ortografico al importarlo.
 *
 * <p><b>Anotacion.</b> Solo puntajes ADJUDICATED del lote mas reciente de cada tipo con adjudicacion vigente
 * (resuelta sobre las importaciones de evaluador vigentes). Si no lo hay, o no cubre todas las ejecuciones
 * o sugerencias incluidas, la metrica es {@code null} y el estado explica por que; nunca se calcula con
 * datos parciales. Si el lote mas reciente del tipo aun no tiene adjudicacion vigente, el estado lo identifica
 * ({@code NOT_ADJUDICATED}) en lugar de sugerir otro lote. PPM y TAS son descriptivos mientras no se configuren
 * δPPM y el limite de TAS (un umbral invalido se trata como ausente).
 */
@Service
public class StudyMetricsService {

    /** Regla de tokenizacion de PEO y PPM (ver Javadoc de la clase). */
    public static final Pattern WORD = Pattern.compile("[^\\W_]+(?:['’\\-][^\\W_]+)*", Pattern.UNICODE_CHARACTER_CLASS);
    static final List<String> ANALYSIS_COLUMNS = List.of(
            "pseudonym", "condition", "task", "protocol_version", "included", "excluded", "run_id", "duration_ms",
            "word_count", "orthography_errors", "orthography_batch_id", "final_text", "suggestion_index", "original_text",
            "suggestion", "semantic_score", "accepted", "semantic_batch_id");
    /** Cuantil 0.975 de la normal estandar usado en el intervalo de Wilson. */
    static final double Z_95 = 1.959964;
    private static final double CONFIDENCE = 0.95;
    private static final String ADJUDICATED = "ADJUDICATED";

    private final ResearchStudyRepository studyRepository;
    private final StudyParticipantRepository participantRepository;
    private final ExperimentRunRepository runRepository;
    private final CorrectionSessionRepository sessionRepository;
    private final AnnotationBatchRepository batchRepository;
    private final AnnotationItemRepository itemRepository;
    private final AnnotationImportRepository importRepository;
    private final ResearchAuditEventRepository auditRepository;
    private final ResearcherRepository researcherRepository;
    private final ObjectMapper objectMapper;
    private final ResearchProperties properties;
    private final Clock clock;

    public StudyMetricsService(
            ResearchStudyRepository studyRepository,
            StudyParticipantRepository participantRepository,
            ExperimentRunRepository runRepository,
            CorrectionSessionRepository sessionRepository,
            AnnotationBatchRepository batchRepository,
            AnnotationItemRepository itemRepository,
            AnnotationImportRepository importRepository,
            ResearchAuditEventRepository auditRepository,
            ResearcherRepository researcherRepository,
            ObjectMapper objectMapper,
            ResearchProperties properties,
            Clock clock) {
        this.studyRepository = studyRepository;
        this.participantRepository = participantRepository;
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.importRepository = importRepository;
        this.auditRepository = auditRepository;
        this.researcherRepository = researcherRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- formulas

    public static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** PEO y PPM de una ejecucion; PEO es {@code null} si el texto no tiene palabras contables. */
    public static RunMetrics runMetrics(String finalText, long durationMs, int orthographyErrors) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        int words = wordCount(finalText);
        Double peo = words == 0 ? null : 100.0 * orthographyErrors / words;
        double ppm = words / (durationMs / 60_000.0);
        return new RunMetrics(words, peo, ppm);
    }

    /** {@code (unassisted - assisted) / unassisted x 100}; no se calcula cuando el PEO sin asistencia es 0. */
    public static Double relativeReduction(double unassisted, double assisted) {
        return unassisted == 0.0 ? null : 100.0 * (unassisted - assisted) / unassisted;
    }

    /** Comparacion emparejada {@code assisted - unassisted}; inferencia solo con n >= 2 y varianza no nula. */
    public static PairedSummary pairedSummary(List<PairValue> pairs) {
        int n = pairs.size();
        if (n == 0) {
            return PairedSummary.empty();
        }
        double[] assisted = pairs.stream().mapToDouble(PairValue::assisted).toArray();
        double[] unassisted = pairs.stream().mapToDouble(PairValue::unassisted).toArray();
        List<Double> deltas = pairs.stream().map(PairValue::delta).toList();
        Interval delta = interval(deltas);
        Double t = null;
        Double p = null;
        Double dz = null;
        if (delta.inferential()) {
            TTest test = new TTest();
            t = test.pairedT(assisted, unassisted);
            p = test.pairedTTest(assisted, unassisted);
            dz = delta.mean() / delta.sd();
        }
        return new PairedSummary(n, mean(assisted), sampleSd(assisted), mean(unassisted), sampleSd(unassisted),
                delta.mean(), delta.sd(), delta.lower(), delta.upper(), t, p, dz);
    }

    /** Media, desviacion muestral e IC 95 % (t) de un valor por participante. */
    record Interval(int n, Double mean, Double sd, Double lower, Double upper) {
        boolean inferential() {
            return lower != null;
        }

        /** Version acotada a [0, 100] para tasas expresadas en porcentaje. */
        Interval clampedToPercent() {
            return new Interval(n, mean, sd, lower == null ? null : clampPercent(lower), upper == null ? null : clampPercent(upper));
        }
    }

    static Interval interval(List<Double> values) {
        int n = values.size();
        if (n == 0) {
            return new Interval(0, null, null, null, null);
        }
        double[] data = values.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = mean(data);
        if (n < 2) {
            return new Interval(n, mean, null, null, null);
        }
        double sd = sampleSd(data);
        if (sd == 0.0) {
            return new Interval(n, mean, sd, null, null);
        }
        double halfWidth = new TDistribution(n - 1).inverseCumulativeProbability(1 - (1 - CONFIDENCE) / 2)
                * sd / Math.sqrt(n);
        return new Interval(n, mean, sd, mean - halfWidth, mean + halfWidth);
    }

    /** Intervalo de Wilson (95 %) de una proporcion agregada, en porcentaje y acotado a [0, 100]. */
    record WilsonInterval(Double lower, Double upper) {
    }

    /**
     * Wilson score interval: {@code centro = (p + z²/2n) / (1 + z²/n)},
     * {@code semiancho = z / (1 + z²/n) · sqrt(p(1 − p)/n + z²/4n²)}, con {@code p = successes / n} y
     * z = {@link #Z_95}. Sin denominador ({@code n == 0}) no hay intervalo.
     */
    static WilsonInterval wilson(long successes, long n) {
        if (n <= 0) {
            return new WilsonInterval(null, null);
        }
        double p = (double) successes / n;
        double z2n = Z_95 * Z_95 / n;
        double center = (p + z2n / 2.0) / (1.0 + z2n);
        double half = Z_95 / (1.0 + z2n) * Math.sqrt(p * (1.0 - p) / n + Z_95 * Z_95 / (4.0 * n * n));
        return new WilsonInterval(clampPercent(100.0 * (center - half)), clampPercent(100.0 * (center + half)));
    }

    private static double clampPercent(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static double mean(double[] data) {
        double sum = 0;
        for (double value : data) {
            sum += value;
        }
        return sum / data.length;
    }

    private static Double sampleSd(double[] data) {
        if (data.length < 2) {
            return null;
        }
        double mean = mean(data);
        double squares = 0;
        for (double value : data) {
            squares += (value - mean) * (value - mean);
        }
        return Math.sqrt(squares / (data.length - 1));
    }

    // ----------------------------------------------------------------- results

    @Transactional(readOnly = true)
    public StudyResultsResponse results(UUID researcherId, UUID studyId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        Analysis analysis = analyze(study);
        Cohort cohort = analysis.cohort();
        // Defensa ante una instancia construida fuera del binding validado: un umbral invalido no publica criterios.
        Double margin = ResearchProperties.validPpmMargin(properties.ppmNonInferiorityMargin())
                ? properties.ppmNonInferiorityMargin() : null;
        Double limit = ResearchProperties.validTasLimit(properties.tasLimit()) ? properties.tasLimit() : null;

        List<ParticipantResult> rows = new ArrayList<>();
        List<PairValue> peoPairs = new ArrayList<>();
        int peoWithoutWords = 0;
        List<Double> reductions = new ArrayList<>();
        int reductionsSkipped = 0;
        List<PairValue> ppmPairs = new ArrayList<>();
        List<Double> tasValues = new ArrayList<>();
        int tasWithout = 0;
        int tasRuns = 0;
        List<Double> tasAcceptedValues = new ArrayList<>();
        int tasAcceptedWithout = 0;
        int tasAcceptedRuns = 0;
        boolean peoReady = analysis.orthography().isAdjudicated();
        boolean tasReady = analysis.semantic().isAdjudicated();
        Map<UUID, List<EvaluatedSuggestion>> byRun = analysis.suggestionsByRun();

        for (ParticipantData participant : cohort.included()) {
            double ppmAssisted = participant.meanPpm(ExperimentCondition.ASSISTED);
            double ppmUnassisted = participant.meanPpm(ExperimentCondition.UNASSISTED);
            ppmPairs.add(new PairValue(ppmAssisted, ppmUnassisted));
            Double peoAssisted = null;
            Double peoUnassisted = null;
            Double peoDelta = null;
            Double reduction = null;
            if (peoReady && participant.hasPeoPair()) {
                peoAssisted = participant.meanPeo(ExperimentCondition.ASSISTED, analysis.errorsByRun());
                peoUnassisted = participant.meanPeo(ExperimentCondition.UNASSISTED, analysis.errorsByRun());
                peoDelta = peoAssisted - peoUnassisted;
                peoPairs.add(new PairValue(peoAssisted, peoUnassisted));
                reduction = relativeReduction(peoUnassisted, peoAssisted);
                if (reduction == null) {
                    reductionsSkipped++;
                } else {
                    reductions.add(reduction);
                }
            } else if (peoReady) {
                peoWithoutWords++;
            }
            Double tas = null;
            Double tasAccepted = null;
            if (tasReady) {
                tas = participant.meanRate(byRun, false);
                tasAccepted = participant.meanRate(byRun, true);
                tasRuns += participant.tasRuns(byRun, false);
                tasAcceptedRuns += participant.tasRuns(byRun, true);
                if (tas == null) {
                    tasWithout++;
                } else {
                    tasValues.add(tas);
                }
                if (tasAccepted == null) {
                    tasAcceptedWithout++;
                } else {
                    tasAcceptedValues.add(tasAccepted);
                }
            }
            rows.add(new ParticipantResult(participant.participant().getPseudonym(), peoAssisted, peoUnassisted,
                    peoDelta, reduction, ppmAssisted, ppmUnassisted, ppmAssisted - ppmUnassisted, tas, tasAccepted));
        }

        PeoResult peo = null;
        if (peoReady && !peoPairs.isEmpty()) {
            PairedSummary paired = pairedSummary(peoPairs);
            Double reductionMean = reductions.isEmpty() ? null : mean(reductions.stream().mapToDouble(d -> d).toArray());
            Boolean upperBelowZero = paired.ci95Upper() == null ? null : paired.ci95Upper() < 0;
            peo = new PeoResult(paired, peoPairs.size(), peoWithoutWords, cohort.peoRuns().size(), reductionMean,
                    reductions.size(), reductionsSkipped, upperBelowZero);
        }
        PpmResult ppm = null;
        if (!ppmPairs.isEmpty()) {
            PairedSummary paired = pairedSummary(ppmPairs);
            Boolean nonInferior = margin == null || paired.ci95Lower() == null ? null : paired.ci95Lower() > -margin;
            ppm = new PpmResult(paired, ppmPairs.size(), cohort.includedRuns().size(), margin == null, margin, nonInferior);
        }
        TasResult tas = null;
        TasResult tasAccepted = null;
        if (tasReady) {
            List<EvaluatedSuggestion> all = analysis.includedSuggestions();
            tas = tasResult(tasValues, tasWithout, tasRuns, all.size(),
                    all.stream().filter(EvaluatedSuggestion::harmful).count(), limit);
            List<EvaluatedSuggestion> accepted = all.stream().filter(EvaluatedSuggestion::isAccepted).toList();
            tasAccepted = tasResult(tasAcceptedValues, tasAcceptedWithout, tasAcceptedRuns, accepted.size(),
                    accepted.stream().filter(EvaluatedSuggestion::harmful).count(), limit);
        }
        return new StudyResultsResponse(study.getId(), study.getCode(), study.getTitle(), cohort.sample(), peo, ppm,
                tas, tasAccepted, analysis.orthography(), analysis.semantic(), rows, provenance(analysis, margin, limit));
    }

    /** El criterio se juzga sobre el limite superior de Wilson de la proporcion agregada; el IC t es descriptivo. */
    private static TasResult tasResult(List<Double> values, int without, int runs, long evaluated, long harmful, Double limit) {
        Interval participants = interval(values).clampedToPercent();
        Double pooled = evaluated == 0 ? null : 100.0 * harmful / evaluated;
        WilsonInterval wilson = wilson(harmful, evaluated);
        Boolean belowLimit = limit == null || wilson.upper() == null ? null : wilson.upper() < limit;
        return new TasResult(participants.n(), without, runs, evaluated, harmful, pooled, wilson.lower(), wilson.upper(),
                participants.mean(), participants.sd(), participants.lower(), participants.upper(),
                limit == null, limit, belowLimit);
    }

    private Provenance provenance(Analysis analysis, Double margin, Double limit) {
        List<ExperimentRun> runs = analysis.cohort().includedRuns();
        List<Dataset> datasets = new ArrayList<>();
        analysis.orthographyBatch().filter(b -> analysis.orthography().isAdjudicated()).map(this::dataset).ifPresent(datasets::add);
        analysis.semanticBatch().filter(b -> analysis.semantic().isAdjudicated()).map(this::dataset).ifPresent(datasets::add);
        return new Provenance(
                runs.stream().map(run -> run.getProtocol().getVersion()).distinct().sorted().toList(),
                distinct(runs, ExperimentRun::getModelVersion),
                distinct(runs, ExperimentRun::getBackendVersion),
                distinct(runs, ExperimentRun::getAppVersion),
                datasets, margin, limit, analysis.sessionsChangedAfterExport(), clock.instant());
    }

    private Dataset dataset(ResolvedBatch resolved) {
        AnnotationBatch batch = resolved.batch();
        AnnotationImport adjudication = resolved.adjudication();
        return new Dataset(batch.getKind(), batch.getId(), batch.getExportSha256(), batch.getRowCount(),
                adjudication.getId(), adjudication.getVersion(), adjudication.getFileSha256());
    }

    private static List<String> distinct(List<ExperimentRun> runs, java.util.function.Function<ExperimentRun, String> field) {
        return runs.stream().map(field).filter(Objects::nonNull).distinct().sorted().toList();
    }

    // --------------------------------------------------------------------- CSV

    /**
     * Exportacion de analisis para los autores (una fila por ejecucion completada y sugerencia evaluada). No es
     * ciega, porque se usa despues de la adjudicacion; contiene seudonimos y texto del alumno, nunca cuentas ni
     * identidad docente. Los puntajes provienen del lote adjudicado vigente de cada tipo (en blanco si no lo hay),
     * identificado en {@code orthography_batch_id} / {@code semantic_batch_id}; {@code accepted} es el valor
     * congelado en el item semantico (en blanco sin item).
     */
    @Transactional
    public AnnotationCsvFile analysisCsv(UUID researcherId, UUID studyId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        Researcher researcher = requireResearcher(researcherId);
        Analysis analysis = analyze(study);
        Cohort cohort = analysis.cohort();
        Map<UUID, Integer> errors = analysis.orthographyBatch().map(ResolvedBatch::scoresByRun).orElse(Map.of());
        String orthographyBatchId = analysis.orthographyBatch().map(b -> b.batch().getId().toString()).orElse("");
        String semanticBatchId = analysis.semanticBatch().map(b -> b.batch().getId().toString()).orElse("");
        List<List<String>> rows = new ArrayList<>();
        for (ParticipantData participant : cohort.participants()) {
            for (ExperimentRun run : participant.completedRuns()) {
                RunMetrics metrics = runMetrics(run.getFinalText(), run.getDurationMs(), 0);
                List<String> base = List.of(
                        participant.participant().getPseudonym(),
                        run.getCondition().name(),
                        run.getTask().getVariant().name(),
                        String.valueOf(run.getProtocol().getVersion()),
                        String.valueOf(cohort.isIncluded(run)),
                        String.valueOf(run.isExcluded()),
                        run.getId().toString(),
                        String.valueOf(run.getDurationMs()),
                        String.valueOf(metrics.wordCount()),
                        text(errors.get(run.getId())),
                        errors.containsKey(run.getId()) ? orthographyBatchId : "",
                        run.getFinalText());
                List<EvaluatedSuggestion> suggestions = analysis.suggestionsByRun().getOrDefault(run.getId(), List.of());
                if (suggestions.isEmpty()) {
                    rows.add(concat(base, List.of("", "", "", "", "", "")));
                }
                for (EvaluatedSuggestion suggestion : suggestions) {
                    rows.add(concat(base, List.of(
                            String.valueOf(suggestion.index()),
                            suggestion.session().getOriginalText(),
                            suggestion.text(),
                            text(suggestion.score()),
                            suggestion.accepted() == null ? "" : String.valueOf(suggestion.accepted()),
                            suggestion.score() == null ? "" : semanticBatchId)));
                }
            }
        }
        byte[] bytes = AnnotationCsv.encode(ANALYSIS_COLUMNS, rows).getBytes(StandardCharsets.UTF_8);
        String hash = ResearchAnnotationService.sha256(bytes);
        auditRepository.save(new ResearchAuditEvent(researcher, study, "STUDY_RESULTS_EXPORTED", study.getId(),
                "rows=" + rows.size() + ", participantsIncluded=" + cohort.sample().participantsIncluded()
                        + ", sha256=" + hash));
        return new AnnotationCsvFile("analysis-" + study.getId().toString().substring(0, 8) + ".csv", bytes, hash);
    }

    private static String text(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        List<String> row = new ArrayList<>(head);
        row.addAll(tail);
        return row;
    }

    // ---------------------------------------------------------------- analysis

    /** Cohorte, lotes adjudicados vigentes y su cobertura; compartido por los resultados y la exportacion. */
    private Analysis analyze(ResearchStudy study) {
        UUID studyId = study.getId();
        List<StudyParticipant> participants = participantRepository.findByStudyIdOrderByPseudonymAsc(studyId);
        List<ExperimentRun> runs = runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId);
        Cohort cohort = Cohort.of(participants, runs);
        List<AnnotationBatch> batches = batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId);
        Optional<AnnotationBatch> newestOrthography = newest(batches, AnnotationKind.ORTHOGRAPHY);
        Optional<AnnotationBatch> newestSemantic = newest(batches, AnnotationKind.SEMANTIC);
        Optional<ResolvedBatch> orthography = currentAdjudicated(batches, AnnotationKind.ORTHOGRAPHY);
        Optional<ResolvedBatch> semantic = currentAdjudicated(batches, AnnotationKind.SEMANTIC);
        Suggestions suggestions = suggestions(cohort, semantic);
        return new Analysis(cohort, orthography, semantic,
                orthographyStatus(cohort, orthography, newestOrthography),
                semanticStatus(cohort, semantic, newestSemantic, suggestions.byRun()),
                orthography.map(ResolvedBatch::scoresByRun).orElse(Map.of()), suggestions.byRun(),
                suggestions.changedAfterExport());
    }

    private static Optional<AnnotationBatch> newest(List<AnnotationBatch> batches, AnnotationKind kind) {
        return batches.stream().filter(batch -> batch.getKind() == kind).findFirst();
    }

    /** Lote mas reciente del tipo con adjudicacion vigente (sobre los evaluadores vigentes) y completa. */
    private Optional<ResolvedBatch> currentAdjudicated(List<AnnotationBatch> batches, AnnotationKind kind) {
        for (AnnotationBatch batch : batches) {
            if (batch.getKind() != kind) {
                continue;
            }
            List<AnnotationImport> imports = importRepository.findByBatchIdOrderByVersionAsc(batch.getId());
            AnnotationImport adjudication = current(imports, AnnotationSlot.ADJUDICATED);
            if (adjudication == null
                    || !adjudication.adjudicates(current(imports, AnnotationSlot.RATER_1), current(imports, AnnotationSlot.RATER_2))) {
                continue;
            }
            List<AnnotationItem> items = itemRepository.findByBatchIdOrderByPositionAsc(batch.getId());
            if (!items.isEmpty() && items.stream().allMatch(item -> item.getAdjudicatedScore() != null)) {
                return Optional.of(new ResolvedBatch(batch, adjudication, items));
            }
        }
        return Optional.empty();
    }

    private static AnnotationImport current(List<AnnotationImport> imports, AnnotationSlot slot) {
        return imports.stream().filter(i -> i.getSlot() == slot && i.isCurrent()).findFirst().orElse(null);
    }

    private static AnnotationStatus orthographyStatus(Cohort cohort, Optional<ResolvedBatch> resolved, Optional<AnnotationBatch> newest) {
        if (cohort.included().isEmpty()) {
            return status("NO_SAMPLE", null, "No participant has a complete pair of included runs");
        }
        List<ExperimentRun> peoRuns = cohort.peoRuns();
        if (peoRuns.isEmpty()) {
            return status("NOT_APPLICABLE", null, "No included participant has countable words in both conditions");
        }
        if (resolved.isEmpty()) {
            return missingAdjudication(newest, "orthography");
        }
        ResolvedBatch batch = resolved.get();
        Map<UUID, Integer> scores = batch.scoresByRun();
        long covered = peoRuns.stream().filter(run -> scores.containsKey(run.getId())).count();
        int total = peoRuns.size();
        if (covered < total) {
            return pendingOrIncomplete(batch, newest, "orthography", covered, total, "included runs");
        }
        return status(ADJUDICATED, batch, "Adjudicated orthography scores cover all " + total + " included runs");
    }

    private static AnnotationStatus semanticStatus(
            Cohort cohort, Optional<ResolvedBatch> resolved, Optional<AnnotationBatch> newest,
            Map<UUID, List<EvaluatedSuggestion>> byRun) {
        if (cohort.included().isEmpty()) {
            return status("NO_SAMPLE", null, "No participant has a complete pair of included runs");
        }
        List<EvaluatedSuggestion> included = cohort.includedRuns().stream()
                .flatMap(run -> byRun.getOrDefault(run.getId(), List.<EvaluatedSuggestion>of()).stream())
                .toList();
        if (included.isEmpty()) {
            return status("NOT_APPLICABLE", null, "The included ASSISTED runs received no suggestions to evaluate");
        }
        if (resolved.isEmpty()) {
            return missingAdjudication(newest, "semantic");
        }
        long covered = included.stream().filter(s -> s.score() != null).count();
        if (covered < included.size()) {
            return pendingOrIncomplete(resolved.get(), newest, "semantic", covered, included.size(), "included suggestions");
        }
        return status(ADJUDICATED, resolved.get(),
                "Adjudicated semantic scores cover all " + included.size() + " included suggestions");
    }

    private static AnnotationStatus missingAdjudication(Optional<AnnotationBatch> newest, String kind) {
        return newest.map(batch -> notAdjudicated(batch, kind, ""))
                .orElseGet(() -> status("NO_BATCH", null, "No " + kind + " annotation batch exists for this study"));
    }

    /**
     * La adjudicacion vigente no cubre la muestra: si ya existe un lote mas reciente sin adjudicacion vigente, el
     * estado lo identifica (hay que adjudicarlo, no crear otro); si no, hace falta un lote nuevo.
     */
    private static AnnotationStatus pendingOrIncomplete(
            ResolvedBatch resolved, Optional<AnnotationBatch> newest, String kind, long covered, int total, String unit) {
        String coverage = covered + " of " + total + " " + unit + " have an adjudicated " + kind + " score";
        if (newest.isPresent() && !newest.get().getId().equals(resolved.batch().getId())) {
            return notAdjudicated(newest.get(), kind, "; the older adjudicated batch " + resolved.batch().getId()
                    + " is not used because only " + coverage);
        }
        return status("INCOMPLETE_COVERAGE", resolved, coverage + " in the current batch; create and adjudicate a new "
                + kind + " batch");
    }

    private static AnnotationStatus notAdjudicated(AnnotationBatch batch, String kind, String detail) {
        return new AnnotationStatus("NOT_ADJUDICATED", batch.getId(), batch.getExportSha256(), null,
                "Batch " + batch.getId() + " is the most recent " + kind
                        + " batch but has no current adjudication over the current rater imports; import its rater"
                        + " and adjudication files" + detail);
    }

    private static AnnotationStatus status(String code, ResolvedBatch batch, String message) {
        return batch == null
                ? new AnnotationStatus(code, null, null, null, message)
                : new AnnotationStatus(code, batch.batch().getId(), batch.batch().getExportSha256(),
                        batch.adjudication().getId(), message);
    }

    /** Sugerencias evaluables por ejecucion y numero de items cuya sesion viva difiere de la aceptacion congelada. */
    private record Suggestions(Map<UUID, List<EvaluatedSuggestion>> byRun, Integer changedAfterExport) {
    }

    /**
     * Sugerencias evaluables (lista ofrecida no vacia) de cada ejecucion completada, con su puntaje vigente. La
     * aceptacion proviene del item semantico congelado ({@code null} sin item): el estado vivo de la sesion solo
     * se compara para informar {@code sessionsChangedAfterExport}.
     */
    private Suggestions suggestions(Cohort cohort, Optional<ResolvedBatch> semantic) {
        List<UUID> runIds = cohort.completedRuns().stream().map(ExperimentRun::getId).toList();
        if (runIds.isEmpty()) {
            return new Suggestions(Map.of(), semantic.isPresent() ? 0 : null);
        }
        Map<UUID, AnnotationItem> itemsBySession = semantic.map(ResolvedBatch::itemsBySession).orElse(Map.of());
        Map<UUID, List<EvaluatedSuggestion>> byRun = new LinkedHashMap<>();
        int changed = 0;
        for (CorrectionSession session : sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(runIds)) {
            List<String> offered = SessionSuggestions.offered(objectMapper, session);
            if (offered.isEmpty()) {
                continue;
            }
            AnnotationItem item = itemsBySession.get(session.getId());
            int index = item != null ? item.getSuggestionIndex() : SessionSuggestions.evaluatedIndex(session, offered);
            Boolean accepted = item == null ? null : item.isAcceptedAtExport();
            Integer score = item == null ? null : item.getAdjudicatedScore();
            if (item != null) {
                int live = SessionSuggestions.acceptedIndex(session, offered);
                if (!Objects.equals(live >= 0 ? live : null, item.getAcceptedIndexAtExport())) {
                    changed++;
                }
            }
            byRun.computeIfAbsent(session.getExperimentRun().getId(), id -> new ArrayList<>())
                    .add(new EvaluatedSuggestion(session, index, offered.get(Math.min(index, offered.size() - 1)), accepted, score));
        }
        return new Suggestions(byRun, semantic.isPresent() ? changed : null);
    }

    private record ResolvedBatch(AnnotationBatch batch, AnnotationImport adjudication, List<AnnotationItem> items) {
        Map<UUID, Integer> scoresByRun() {
            Map<UUID, Integer> scores = new HashMap<>();
            items.forEach(item -> scores.put(item.getRun().getId(), item.getAdjudicatedScore()));
            return scores;
        }

        Map<UUID, AnnotationItem> itemsBySession() {
            return items.stream()
                    .filter(item -> item.getCorrectionSession() != null)
                    .collect(Collectors.toMap(item -> item.getCorrectionSession().getId(), item -> item, (a, b) -> a));
        }
    }

    /** {@code accepted} es el valor congelado en el lote semantico; {@code null} cuando no hay item. */
    private record EvaluatedSuggestion(CorrectionSession session, int index, String text, Boolean accepted, Integer score) {
        boolean harmful() {
            return score != null && score == 0;
        }

        boolean isAccepted() {
            return Boolean.TRUE.equals(accepted);
        }
    }

    private record Analysis(
            Cohort cohort,
            Optional<ResolvedBatch> orthographyBatch,
            Optional<ResolvedBatch> semanticBatch,
            AnnotationStatus orthography,
            AnnotationStatus semantic,
            Map<UUID, Integer> errorsByRun,
            Map<UUID, List<EvaluatedSuggestion>> suggestionsByRun,
            Integer sessionsChangedAfterExport) {

        List<EvaluatedSuggestion> includedSuggestions() {
            return cohort.includedRuns().stream()
                    .flatMap(run -> suggestionsByRun.getOrDefault(run.getId(), List.<EvaluatedSuggestion>of()).stream())
                    .toList();
        }
    }

    // ------------------------------------------------------------------ cohort

    /** Ejecuciones completadas de un participante, separadas en elegibles (no excluidas) por condicion. */
    private record ParticipantData(StudyParticipant participant, List<ExperimentRun> completedRuns,
                                   Map<ExperimentCondition, List<ExperimentRun>> eligible) {

        boolean hasCompletePair() {
            return !eligible.get(ExperimentCondition.ASSISTED).isEmpty()
                    && !eligible.get(ExperimentCondition.UNASSISTED).isEmpty();
        }

        List<ExperimentRun> eligibleRuns() {
            List<ExperimentRun> runs = new ArrayList<>(eligible.get(ExperimentCondition.ASSISTED));
            runs.addAll(eligible.get(ExperimentCondition.UNASSISTED));
            return runs;
        }

        /** Ejecuciones con palabras contables (denominador de PEO definido). */
        List<ExperimentRun> peoRuns(ExperimentCondition condition) {
            return eligible.get(condition).stream().filter(run -> wordCount(run.getFinalText()) > 0).toList();
        }

        boolean hasPeoPair() {
            return !peoRuns(ExperimentCondition.ASSISTED).isEmpty() && !peoRuns(ExperimentCondition.UNASSISTED).isEmpty();
        }

        List<ExperimentRun> peoRuns() {
            List<ExperimentRun> runs = new ArrayList<>(peoRuns(ExperimentCondition.ASSISTED));
            runs.addAll(peoRuns(ExperimentCondition.UNASSISTED));
            return runs;
        }

        /** PPM sobre todas las ejecuciones elegibles: una sin palabras contables vale 0. */
        double meanPpm(ExperimentCondition condition) {
            return eligible.get(condition).stream()
                    .mapToDouble(run -> runMetrics(run.getFinalText(), run.getDurationMs(), 0).ppm())
                    .average().orElseThrow();
        }

        double meanPeo(ExperimentCondition condition, Map<UUID, Integer> errorsByRun) {
            return peoRuns(condition).stream()
                    .mapToDouble(run -> runMetrics(run.getFinalText(), run.getDurationMs(), errorsByRun.get(run.getId())).peo())
                    .average().orElseThrow();
        }

        /** Media de la tasa por ejecucion ASSISTED (perjudiciales / evaluadas o aceptadas); null sin denominador. */
        Double meanRate(Map<UUID, List<EvaluatedSuggestion>> byRun, boolean acceptedOnly) {
            List<Double> rates = new ArrayList<>();
            for (ExperimentRun run : eligible.get(ExperimentCondition.ASSISTED)) {
                List<EvaluatedSuggestion> suggestions = tasSuggestions(byRun, run, acceptedOnly);
                if (!suggestions.isEmpty()) {
                    rates.add(100.0 * suggestions.stream().filter(EvaluatedSuggestion::harmful).count() / suggestions.size());
                }
            }
            return rates.isEmpty() ? null : rates.stream().mapToDouble(d -> d).average().orElseThrow();
        }

        /** Ejecuciones ASSISTED con al menos una sugerencia evaluada (o aceptada). */
        int tasRuns(Map<UUID, List<EvaluatedSuggestion>> byRun, boolean acceptedOnly) {
            return (int) eligible.get(ExperimentCondition.ASSISTED).stream()
                    .filter(run -> !tasSuggestions(byRun, run, acceptedOnly).isEmpty())
                    .count();
        }

        private static List<EvaluatedSuggestion> tasSuggestions(
                Map<UUID, List<EvaluatedSuggestion>> byRun, ExperimentRun run, boolean acceptedOnly) {
            return byRun.getOrDefault(run.getId(), List.of()).stream()
                    .filter(s -> !acceptedOnly || s.isAccepted())
                    .toList();
        }
    }

    private record Cohort(List<ParticipantData> participants, List<ParticipantData> included, Sample sample) {

        static Cohort of(List<StudyParticipant> all, List<ExperimentRun> runs) {
            Map<UUID, List<ExperimentRun>> byParticipant = new LinkedHashMap<>();
            all.forEach(p -> byParticipant.put(p.getId(), new ArrayList<>()));
            runs.stream().filter(ExperimentRun::isCompleted)
                    .forEach(run -> byParticipant.computeIfAbsent(run.getParticipant().getId(), id -> new ArrayList<>()).add(run));
            Map<UUID, StudyParticipant> participantsById = new HashMap<>();
            all.forEach(p -> participantsById.put(p.getId(), p));
            runs.forEach(run -> participantsById.putIfAbsent(run.getParticipant().getId(), run.getParticipant()));

            List<ParticipantData> participants = new ArrayList<>();
            int runsExcluded = 0;
            for (Map.Entry<UUID, List<ExperimentRun>> entry : byParticipant.entrySet()) {
                Map<ExperimentCondition, List<ExperimentRun>> eligible = new EnumMap<>(ExperimentCondition.class);
                for (ExperimentCondition condition : ExperimentCondition.values()) {
                    eligible.put(condition, new ArrayList<>());
                }
                for (ExperimentRun run : entry.getValue()) {
                    if (run.isExcluded()) {
                        runsExcluded++;
                    } else {
                        eligible.get(run.getCondition()).add(run);
                    }
                }
                participants.add(new ParticipantData(participantsById.get(entry.getKey()), entry.getValue(), eligible));
            }
            participants.sort(Comparator.comparing((ParticipantData p) -> p.participant().getPseudonym()));
            List<ParticipantData> included = participants.stream().filter(ParticipantData::hasCompletePair).toList();
            List<ParticipantData> incomplete = participants.stream()
                    .filter(p -> !p.hasCompletePair() && !p.eligibleRuns().isEmpty())
                    .toList();
            int withoutEligible = participants.size() - included.size() - incomplete.size();
            int runsCompleted = participants.stream().mapToInt(p -> p.completedRuns().size()).sum();
            int runsIncluded = included.stream().mapToInt(p -> p.eligibleRuns().size()).sum();
            int runsIncomplete = incomplete.stream().mapToInt(p -> p.eligibleRuns().size()).sum();
            int runsWithoutWords = (int) included.stream().flatMap(p -> p.eligibleRuns().stream())
                    .filter(run -> wordCount(run.getFinalText()) == 0).count();
            Sample sample = new Sample(participants.size(), included.size(), incomplete.size(), withoutEligible,
                    runsCompleted, runsIncluded, runsExcluded, runsIncomplete, runsWithoutWords);
            return new Cohort(participants, included, sample);
        }

        List<ExperimentRun> includedRuns() {
            return included.stream().flatMap(p -> p.eligibleRuns().stream()).toList();
        }

        /** Ejecuciones que PEO usa: las de participantes con palabras contables en ambas condiciones. */
        List<ExperimentRun> peoRuns() {
            return included.stream().filter(ParticipantData::hasPeoPair).flatMap(p -> p.peoRuns().stream()).toList();
        }

        List<ExperimentRun> completedRuns() {
            return participants.stream().flatMap(p -> p.completedRuns().stream()).toList();
        }

        boolean isIncluded(ExperimentRun run) {
            return included.stream().anyMatch(p -> p.eligibleRuns().contains(run));
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Un estudio ajeno responde igual que uno inexistente para no permitir enumerarlos. */
    private ResearchStudy requireOwnedStudy(UUID researcherId, UUID studyId) {
        return studyRepository.findByIdAndCreatedById(studyId, researcherId)
                .orElseThrow(() -> new NotFoundException("Study not found"));
    }

    private Researcher requireResearcher(UUID researcherId) {
        return researcherRepository.findById(researcherId)
                .orElseThrow(() -> new NotFoundException("Researcher not found"));
    }
}
