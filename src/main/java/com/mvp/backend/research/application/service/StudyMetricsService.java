package com.mvp.backend.research.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p><b>Muestra.</b> Solo ejecuciones COMPLETED no excluidas cuyo participante tiene ambas condiciones
 * ("par completo"). Varias ejecuciones de un mismo participante y condicion se promedian a un valor antes
 * de la inferencia emparejada; la unidad de analisis es el participante.
 *
 * <p><b>Formulas.</b> {@code PEO = errores ortograficos adjudicados / palabras del texto final x 100};
 * {@code PPM = palabras / (duracion_ms / 60000)}; {@code TAS = sugerencias adjudicadas con 0 / evaluadas
 * x 100}; {@code TAS aceptada = perjudiciales aceptadas / aceptadas x 100}. Diferencia emparejada
 * {@code ASSISTED - UNASSISTED}, IC 95 % con distribucion t, valor p bilateral (prueba t emparejada) y
 * d_z de Cohen = media(delta) / desviacion(delta).
 *
 * <p><b>Tokenizacion documentada</b> ({@link #WORD}): una palabra es una secuencia de letras o digitos
 * Unicode, con apostrofes o guiones internos ("l'amour", "re-hacer" cuentan una vez); la puntuacion y los
 * simbolos no cuentan. La misma regla acota el puntaje ortografico al importarlo.
 *
 * <p><b>Anotacion.</b> Solo puntajes ADJUDICATED del lote mas reciente de cada tipo con adjudicacion vigente
 * (resuelta sobre las importaciones de evaluador vigentes). Si no lo hay, o no cubre todas las ejecuciones
 * o sugerencias incluidas, la metrica es {@code null} y el estado explica por que; nunca se calcula con
 * datos parciales. PPM y TAS son descriptivos mientras no se configuren δPPM y el limite de TAS.
 */
@Service
public class StudyMetricsService {

    /** Regla de tokenizacion de PEO y PPM (ver Javadoc de la clase). */
    public static final Pattern WORD = Pattern.compile("[^\\W_]+(?:['’\\-][^\\W_]+)*", Pattern.UNICODE_CHARACTER_CLASS);
    static final List<String> ANALYSIS_COLUMNS = List.of(
            "pseudonym", "condition", "task", "protocol_version", "included", "excluded", "run_id", "duration_ms",
            "word_count", "orthography_errors", "final_text", "suggestion_index", "original_text", "suggestion",
            "semantic_score", "accepted");
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
        Double margin = properties.ppmNonInferiorityMargin();
        Double limit = properties.tasLimit();

        List<ParticipantResult> rows = new ArrayList<>();
        List<PairValue> peoPairs = new ArrayList<>();
        List<Double> reductions = new ArrayList<>();
        int reductionsSkipped = 0;
        List<PairValue> ppmPairs = new ArrayList<>();
        List<Double> tasValues = new ArrayList<>();
        int tasWithout = 0;
        List<Double> tasAcceptedValues = new ArrayList<>();
        int tasAcceptedWithout = 0;
        boolean peoReady = analysis.orthography().isAdjudicated();
        boolean tasReady = analysis.semantic().isAdjudicated();

        for (ParticipantData participant : cohort.included()) {
            double ppmAssisted = participant.meanPpm(ExperimentCondition.ASSISTED);
            double ppmUnassisted = participant.meanPpm(ExperimentCondition.UNASSISTED);
            ppmPairs.add(new PairValue(ppmAssisted, ppmUnassisted));
            Double peoAssisted = null;
            Double peoUnassisted = null;
            Double peoDelta = null;
            Double reduction = null;
            if (peoReady) {
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
            }
            Double tas = null;
            Double tasAccepted = null;
            if (tasReady) {
                tas = participant.meanRate(analysis.suggestionsByRun(), false);
                tasAccepted = participant.meanRate(analysis.suggestionsByRun(), true);
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
            peo = new PeoResult(paired, reductionMean, reductions.size(), reductionsSkipped, upperBelowZero);
        }
        PpmResult ppm = null;
        if (!ppmPairs.isEmpty()) {
            PairedSummary paired = pairedSummary(ppmPairs);
            Boolean nonInferior = margin == null || paired.ci95Lower() == null ? null : paired.ci95Lower() > -margin;
            ppm = new PpmResult(paired, margin == null, margin, nonInferior);
        }
        TasResult tas = null;
        TasResult tasAccepted = null;
        if (tasReady) {
            List<EvaluatedSuggestion> all = analysis.includedSuggestions();
            tas = tasResult(tasValues, tasWithout, all.size(), all.stream().filter(EvaluatedSuggestion::harmful).count(), limit);
            List<EvaluatedSuggestion> accepted = all.stream().filter(EvaluatedSuggestion::accepted).toList();
            tasAccepted = tasResult(tasAcceptedValues, tasAcceptedWithout, accepted.size(),
                    accepted.stream().filter(EvaluatedSuggestion::harmful).count(), limit);
        }
        return new StudyResultsResponse(study.getId(), study.getCode(), study.getTitle(), cohort.sample(), peo, ppm,
                tas, tasAccepted, analysis.orthography(), analysis.semantic(), rows, provenance(analysis, margin, limit));
    }

    private static TasResult tasResult(List<Double> values, int without, long evaluated, long harmful, Double limit) {
        Interval interval = interval(values);
        Double pooled = evaluated == 0 ? null : 100.0 * harmful / evaluated;
        Boolean belowLimit = limit == null || interval.upper() == null ? null : interval.upper() < limit;
        return new TasResult(interval.n(), without, evaluated, harmful, pooled, interval.mean(), interval.sd(),
                interval.lower(), interval.upper(), limit == null, limit, belowLimit);
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
                datasets, margin, limit, clock.instant());
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
     * identidad docente. Los puntajes provienen del lote adjudicado vigente de cada tipo (en blanco si no lo hay).
     */
    @Transactional
    public AnnotationCsvFile analysisCsv(UUID researcherId, UUID studyId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        Researcher researcher = requireResearcher(researcherId);
        Analysis analysis = analyze(study);
        Cohort cohort = analysis.cohort();
        Map<UUID, Integer> errors = analysis.orthographyBatch().map(ResolvedBatch::scoresByRun).orElse(Map.of());
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
                        run.getFinalText());
                List<EvaluatedSuggestion> suggestions = analysis.suggestionsByRun().getOrDefault(run.getId(), List.of());
                if (suggestions.isEmpty()) {
                    rows.add(concat(base, List.of("", "", "", "", "")));
                }
                for (EvaluatedSuggestion suggestion : suggestions) {
                    rows.add(concat(base, List.of(
                            String.valueOf(suggestion.index()),
                            suggestion.session().getOriginalText(),
                            suggestion.text(),
                            text(suggestion.score()),
                            String.valueOf(suggestion.accepted()))));
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
        Optional<ResolvedBatch> orthography = currentAdjudicated(batches, AnnotationKind.ORTHOGRAPHY);
        Optional<ResolvedBatch> semantic = currentAdjudicated(batches, AnnotationKind.SEMANTIC);
        Map<UUID, List<EvaluatedSuggestion>> suggestionsByRun = suggestionsByRun(cohort, semantic);
        int semanticBatches = (int) batches.stream().filter(b -> b.getKind() == AnnotationKind.SEMANTIC).count();
        int orthographyBatches = batches.size() - semanticBatches;
        return new Analysis(cohort, orthography, semantic,
                orthographyStatus(cohort, orthography, orthographyBatches),
                semanticStatus(cohort, semantic, semanticBatches, suggestionsByRun),
                orthography.map(ResolvedBatch::scoresByRun).orElse(Map.of()), suggestionsByRun);
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

    private static AnnotationStatus orthographyStatus(Cohort cohort, Optional<ResolvedBatch> resolved, int batchCount) {
        if (cohort.included().isEmpty()) {
            return status("NO_SAMPLE", null, "No participant has a complete pair of included runs");
        }
        if (resolved.isEmpty()) {
            return missingAdjudication(batchCount, "orthography");
        }
        ResolvedBatch batch = resolved.get();
        Map<UUID, Integer> scores = batch.scoresByRun();
        long covered = cohort.includedRuns().stream().filter(run -> scores.containsKey(run.getId())).count();
        int total = cohort.includedRuns().size();
        if (covered < total) {
            return status("INCOMPLETE_COVERAGE", batch, covered + " of " + total
                    + " included runs have an adjudicated orthography score in the current batch; "
                    + "create and adjudicate a new orthography batch");
        }
        return status(ADJUDICATED, batch, "Adjudicated orthography scores cover all " + total + " included runs");
    }

    private static AnnotationStatus semanticStatus(
            Cohort cohort, Optional<ResolvedBatch> resolved, int batchCount, Map<UUID, List<EvaluatedSuggestion>> byRun) {
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
            return missingAdjudication(batchCount, "semantic");
        }
        long covered = included.stream().filter(s -> s.score() != null).count();
        if (covered < included.size()) {
            return status("INCOMPLETE_COVERAGE", resolved.get(), covered + " of " + included.size()
                    + " included suggestions have an adjudicated semantic score in the current batch; "
                    + "create and adjudicate a new semantic batch");
        }
        return status(ADJUDICATED, resolved.get(),
                "Adjudicated semantic scores cover all " + included.size() + " included suggestions");
    }

    private static AnnotationStatus missingAdjudication(int batchCount, String kind) {
        return batchCount == 0
                ? status("NO_BATCH", null, "No " + kind + " annotation batch exists for this study")
                : status("NOT_ADJUDICATED", null, batchCount + " " + kind
                        + " batch(es) exist but none has a current adjudication over the current rater imports");
    }

    private static AnnotationStatus status(String code, ResolvedBatch batch, String message) {
        return batch == null
                ? new AnnotationStatus(code, null, null, null, message)
                : new AnnotationStatus(code, batch.batch().getId(), batch.batch().getExportSha256(),
                        batch.adjudication().getId(), message);
    }

    /** Sugerencias evaluables (lista ofrecida no vacia) de cada ejecucion completada, con su puntaje vigente. */
    private Map<UUID, List<EvaluatedSuggestion>> suggestionsByRun(Cohort cohort, Optional<ResolvedBatch> semantic) {
        List<UUID> runIds = cohort.completedRuns().stream().map(ExperimentRun::getId).toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AnnotationItem> itemsBySession = semantic.map(ResolvedBatch::itemsBySession).orElse(Map.of());
        Map<UUID, List<EvaluatedSuggestion>> byRun = new LinkedHashMap<>();
        for (CorrectionSession session : sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(runIds)) {
            List<String> offered = SessionSuggestions.offered(objectMapper, session);
            if (offered.isEmpty()) {
                continue;
            }
            AnnotationItem item = itemsBySession.get(session.getId());
            int index = item != null ? item.getSuggestionIndex() : SessionSuggestions.evaluatedIndex(session, offered);
            boolean accepted = SessionSuggestions.acceptedIndex(session, offered) == index;
            Integer score = item == null ? null : item.getAdjudicatedScore();
            byRun.computeIfAbsent(session.getExperimentRun().getId(), id -> new ArrayList<>())
                    .add(new EvaluatedSuggestion(session, index, offered.get(Math.min(index, offered.size() - 1)), accepted, score));
        }
        return byRun;
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

    private record EvaluatedSuggestion(CorrectionSession session, int index, String text, boolean accepted, Integer score) {
        boolean harmful() {
            return score != null && score == 0;
        }
    }

    private record Analysis(
            Cohort cohort,
            Optional<ResolvedBatch> orthographyBatch,
            Optional<ResolvedBatch> semanticBatch,
            AnnotationStatus orthography,
            AnnotationStatus semantic,
            Map<UUID, Integer> errorsByRun,
            Map<UUID, List<EvaluatedSuggestion>> suggestionsByRun) {

        List<EvaluatedSuggestion> includedSuggestions() {
            return cohort.includedRuns().stream()
                    .flatMap(run -> suggestionsByRun.getOrDefault(run.getId(), List.<EvaluatedSuggestion>of()).stream())
                    .toList();
        }
    }

    // ------------------------------------------------------------------ cohort

    /** Ejecuciones completadas de un participante, separadas en elegibles por condicion. */
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

        double meanPpm(ExperimentCondition condition) {
            return eligible.get(condition).stream()
                    .mapToDouble(run -> runMetrics(run.getFinalText(), run.getDurationMs(), 0).ppm())
                    .average().orElseThrow();
        }

        double meanPeo(ExperimentCondition condition, Map<UUID, Integer> errorsByRun) {
            return eligible.get(condition).stream()
                    .mapToDouble(run -> runMetrics(run.getFinalText(), run.getDurationMs(), errorsByRun.get(run.getId())).peo())
                    .average().orElseThrow();
        }

        /** Media de la tasa por ejecucion ASSISTED (perjudiciales / evaluadas o aceptadas); null sin denominador. */
        Double meanRate(Map<UUID, List<EvaluatedSuggestion>> byRun, boolean acceptedOnly) {
            List<Double> rates = new ArrayList<>();
            for (ExperimentRun run : eligible.get(ExperimentCondition.ASSISTED)) {
                List<EvaluatedSuggestion> suggestions = byRun.getOrDefault(run.getId(), List.of()).stream()
                        .filter(s -> !acceptedOnly || s.accepted())
                        .toList();
                if (!suggestions.isEmpty()) {
                    rates.add(100.0 * suggestions.stream().filter(EvaluatedSuggestion::harmful).count() / suggestions.size());
                }
            }
            return rates.isEmpty() ? null : rates.stream().mapToDouble(d -> d).average().orElseThrow();
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
            int runsWithoutWords = 0;
            for (Map.Entry<UUID, List<ExperimentRun>> entry : byParticipant.entrySet()) {
                Map<ExperimentCondition, List<ExperimentRun>> eligible = new java.util.EnumMap<>(ExperimentCondition.class);
                for (ExperimentCondition condition : ExperimentCondition.values()) {
                    eligible.put(condition, new ArrayList<>());
                }
                for (ExperimentRun run : entry.getValue()) {
                    if (run.isExcluded()) {
                        runsExcluded++;
                    } else if (wordCount(run.getFinalText()) == 0) {
                        runsWithoutWords++;
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
            int runsCompleted = participants.stream().mapToInt(p -> p.completedRuns().size()).sum();
            int runsIncluded = included.stream().mapToInt(p -> p.eligibleRuns().size()).sum();
            int runsIncomplete = incomplete.stream().mapToInt(p -> p.eligibleRuns().size()).sum();
            Sample sample = new Sample(all.size(), included.size(), incomplete.size(), runsCompleted, runsIncluded,
                    runsExcluded, runsIncomplete, runsWithoutWords);
            return new Cohort(participants, included, sample);
        }

        List<ExperimentRun> includedRuns() {
            return included.stream().flatMap(p -> p.eligibleRuns().stream()).toList();
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
