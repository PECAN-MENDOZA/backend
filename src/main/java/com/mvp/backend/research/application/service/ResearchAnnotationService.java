package com.mvp.backend.research.application.service;

import static com.mvp.backend.shared.persistence.ConstraintViolations.violates;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.application.dto.AgreementSummary;
import com.mvp.backend.research.application.dto.AnnotationBatchResponse;
import com.mvp.backend.research.application.dto.AnnotationCsvFile;
import com.mvp.backend.research.domain.model.AnnotationBatch;
import com.mvp.backend.research.domain.model.AnnotationImport;
import com.mvp.backend.research.domain.model.AnnotationItem;
import com.mvp.backend.research.domain.model.AnnotationKind;
import com.mvp.backend.research.domain.model.AnnotationSlot;
import com.mvp.backend.research.domain.model.ResearchAuditEvent;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.AnnotationBatchRepository;
import com.mvp.backend.research.domain.repository.AnnotationImportRepository;
import com.mvp.backend.research.domain.repository.AnnotationItemRepository;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;

/**
 * Evaluacion humana ciega (spec §10). La exportacion contiene solo codigos de muestra aleatorios y
 * el texto a evaluar: nunca condicion, seudonimo, orden, version del modelo ni resultado esperado.
 * La correspondencia muestra -> ejecucion/sesion vive unicamente en {@code annotation_items}.
 *
 * <p>Sugerencia evaluada en lotes semanticos ({@link SessionSuggestions}): la que el alumno acepto
 * ({@code selectedSuggestion}) cuando la sesion registro aceptacion; en cualquier otro caso (rechazo,
 * UNDO, sin feedback) la primera sugerencia ofrecida, que es la que el teclado marca como recomendada.
 * Asi TAS mide la sugerencia con efecto real sobre el texto y, si no la hubo, la que el modelo propuso
 * en primer lugar.
 */
@Service
public class ResearchAnnotationService {

    static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;
    static final List<String> IMPORT_HEADER = List.of("sample_code", "score");
    private static final String SAMPLE_CODE_PREFIX = "T-";
    private static final int SAMPLE_CODE_LENGTH = 8;
    private static final int MAX_LISTED_CODES = 20;
    private static final Pattern SCORE = Pattern.compile("\\d{1,9}");
    private static final String SAMPLE_CODE_CONSTRAINT = "uk_annotation_sample_code";
    private static final String IMPORT_VERSION_CONSTRAINT = "uk_ann_import_version";
    private static final String IMPORT_CURRENT_CONSTRAINT = "uk_ann_import_current";

    private final ResearchStudyRepository studyRepository;
    private final ExperimentRunRepository runRepository;
    private final CorrectionSessionRepository sessionRepository;
    private final AnnotationBatchRepository batchRepository;
    private final AnnotationItemRepository itemRepository;
    private final AnnotationImportRepository importRepository;
    private final ResearchAuditEventRepository auditRepository;
    private final ResearcherRepository researcherRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public ResearchAnnotationService(
            ResearchStudyRepository studyRepository,
            ExperimentRunRepository runRepository,
            CorrectionSessionRepository sessionRepository,
            AnnotationBatchRepository batchRepository,
            AnnotationItemRepository itemRepository,
            AnnotationImportRepository importRepository,
            ResearchAuditEventRepository auditRepository,
            ResearcherRepository researcherRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.studyRepository = studyRepository;
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.importRepository = importRepository;
        this.auditRepository = auditRepository;
        this.researcherRepository = researcherRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ----------------------------------------------------------------- batches

    @Transactional(readOnly = true)
    public List<AnnotationBatchResponse> listBatches(UUID researcherId, UUID studyId) {
        requireOwnedStudy(researcherId, studyId);
        return batchRepository.findByStudyIdOrderByCreatedAtDesc(studyId).stream()
                .map(batch -> summarize(batch, itemRepository.findByBatchIdOrderByPositionAsc(batch.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AnnotationBatchResponse getBatch(UUID researcherId, UUID studyId, UUID batchId) {
        requireOwnedStudy(researcherId, studyId);
        AnnotationBatch batch = requireBatch(studyId, batchId);
        return summarize(batch, itemRepository.findByBatchIdOrderByPositionAsc(batchId));
    }

    /**
     * Congela un lote con las ejecuciones COMPLETED no excluidas del estudio: codigos aleatorios,
     * filas barajadas y hash del CSV. Una ejecucion excluida despues no altera el lote (sigue siendo
     * reproducible); un lote nuevo puede crearse cuando haya mas ejecuciones.
     */
    @Transactional
    public AnnotationBatchResponse createBatch(UUID researcherId, UUID studyId, AnnotationKind kind) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        Researcher researcher = requireResearcher(researcherId);
        List<ExperimentRun> runs = runRepository.findByParticipantStudyIdOrderByCreatedAtAsc(studyId).stream()
                .filter(run -> run.isCompleted() && !run.isExcluded())
                .toList();
        if (runs.isEmpty()) {
            throw new BusinessException("Study has no completed runs to annotate");
        }
        AnnotationBatch batch = new AnnotationBatch(study, kind, researcher, clock.instant());
        List<AnnotationItem> items = switch (kind) {
            case ORTHOGRAPHY -> orthographyItems(batch, runs);
            case SEMANTIC -> semanticItems(batch, runs);
        };
        if (items.isEmpty()) {
            throw new BusinessException("Study has no suggestions to annotate");
        }
        String csv = AnnotationCsv.encode(kind.columns(), items.stream().map(this::row).toList());
        String hash = sha256(csv.getBytes(StandardCharsets.UTF_8));
        batch.freeze(items.size(), hash);
        batchRepository.save(batch);
        try {
            itemRepository.saveAll(items);
            itemRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, SAMPLE_CODE_CONSTRAINT)) {
                throw new ConflictException("Sample code collision, retry the batch creation");
            }
            throw e;
        }
        audit(researcher, study, "ANNOTATION_BATCH_CREATED", batch.getId(),
                "kind=" + kind + ", rows=" + items.size() + ", sha256=" + hash);
        return summarize(batch, items);
    }

    private List<AnnotationItem> orthographyItems(AnnotationBatch batch, List<ExperimentRun> runs) {
        List<AnnotationItem> items = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (ExperimentRun run : shuffled(runs)) {
            if (run.getFinalText() == null || run.getFinalText().isBlank()) {
                continue;
            }
            items.add(new AnnotationItem(batch, uniqueCode(codes), items.size(), run, null, null));
        }
        return items;
    }

    private List<AnnotationItem> semanticItems(AnnotationBatch batch, List<ExperimentRun> runs) {
        Map<UUID, ExperimentRun> byId = runs.stream()
                .collect(Collectors.toMap(ExperimentRun::getId, run -> run, (a, b) -> a, LinkedHashMap::new));
        List<CorrectionSession> sessions = sessionRepository.findByExperimentRunIdInOrderByCreatedAtAsc(
                new ArrayList<>(byId.keySet()));
        List<AnnotationItem> items = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (CorrectionSession session : shuffled(sessions)) {
            List<String> offered = offeredSuggestions(session);
            if (offered.isEmpty()) {
                continue; // la IA no propuso nada evaluable
            }
            ExperimentRun run = byId.get(session.getExperimentRun().getId());
            if (run == null) {
                continue;
            }
            items.add(new AnnotationItem(batch, uniqueCode(codes), items.size(), run, session,
                    SessionSuggestions.evaluatedIndex(session, offered)));
        }
        return items;
    }

    private <T> List<T> shuffled(List<T> source) {
        List<T> copy = new ArrayList<>(source);
        Collections.shuffle(copy, secureRandom);
        return copy;
    }

    private String uniqueCode(Set<String> taken) {
        while (true) {
            StringBuilder code = new StringBuilder(SAMPLE_CODE_PREFIX);
            for (int i = 0; i < SAMPLE_CODE_LENGTH; i++) {
                code.append(AccessCode.ALPHABET.charAt(secureRandom.nextInt(AccessCode.ALPHABET.length())));
            }
            if (taken.add(code.toString())) {
                return code.toString();
            }
        }
    }

    // ------------------------------------------------------------------ export

    /** Reconstruye el CSV desde las filas del lote y verifica que siga coincidiendo con el hash congelado. */
    @Transactional
    public AnnotationCsvFile export(UUID researcherId, UUID studyId, UUID batchId) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        Researcher researcher = requireResearcher(researcherId);
        AnnotationBatch batch = requireBatch(studyId, batchId);
        List<AnnotationItem> items = itemRepository.findByBatchIdOrderByPositionAsc(batchId);
        byte[] bytes = AnnotationCsv.encode(batch.getKind().columns(), items.stream().map(this::row).toList())
                .getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        if (items.size() != batch.getRowCount() || !hash.equals(batch.getExportSha256())) {
            throw new ConflictException("Batch content no longer matches its export hash");
        }
        audit(researcher, study, "ANNOTATION_BATCH_EXPORTED", batchId, "kind=" + batch.getKind() + ", sha256=" + hash);
        String filename = "annotations-" + batch.getKind().name().toLowerCase(Locale.ROOT) + "-"
                + batchId.toString().substring(0, 8) + ".csv";
        return new AnnotationCsvFile(filename, bytes, hash);
    }

    private List<String> row(AnnotationItem item) {
        return switch (item.getBatch().getKind()) {
            case ORTHOGRAPHY -> List.of(item.getSampleCode(), item.getRun().getFinalText(), "");
            case SEMANTIC -> {
                CorrectionSession session = item.getCorrectionSession();
                List<String> offered = offeredSuggestions(session);
                int index = Math.min(item.getSuggestionIndex(), offered.size() - 1);
                yield List.of(item.getSampleCode(), session.getOriginalText(), offered.get(index), "");
            }
        };
    }

    private List<String> offeredSuggestions(CorrectionSession session) {
        return SessionSuggestions.offered(objectMapper, session);
    }

    // ------------------------------------------------------------------ import

    /**
     * Valida e importa un archivo de puntajes para una ranura. Todas las filas del lote deben venir con
     * puntaje (sin faltantes ni codigos desconocidos o repetidos). Una reimportacion crea una version
     * nueva y marca la anterior como superada; nada se borra.
     *
     * <p>El archivo se decodifica y parsea antes de tocar la base; despues se toma el bloqueo de fila
     * del lote y se mantiene durante las comprobaciones de evaluador, vigencia, version y la
     * actualizacion de puntajes, de modo que dos importaciones simultaneas (misma u otra ranura) se
     * serializan. Una ADJUDICATED exige las dos ranuras de evaluador vigentes y completas y queda
     * ligada a ellas; reimportar RATER_1 o RATER_2 supera la adjudicacion vigente y vacia sus puntajes.
     */
    @Transactional
    public AnnotationBatchResponse importScores(
            UUID researcherId, UUID studyId, UUID batchId, AnnotationSlot slot, String rater, byte[] content) {
        ResearchStudy study = requireOwnedStudy(researcherId, studyId);
        Researcher researcher = requireResearcher(researcherId);
        String raterName = requireRater(rater);
        if (content == null || content.length > MAX_IMPORT_BYTES) {
            throw new BusinessException("File must be a UTF-8 CSV of at most 5 MB");
        }
        String text = AnnotationCsv.decode(content);
        List<List<String>> rows = AnnotationCsv.parse(text);
        String hash = sha256(content);

        AnnotationBatch batch = requireBatchForUpdate(studyId, batchId);
        List<AnnotationItem> items = itemRepository.findByBatchIdOrderByPositionAsc(batchId);
        Map<String, Integer> scores = parseScores(batch.getKind(), rows, items);
        requireDistinctRater(batchId, slot, raterName);
        importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(batchId, slot)
                .filter(current -> current.getFileSha256().equals(hash))
                .ifPresent(current -> {
                    throw new ConflictException("This file is already the current import for slot " + slot);
                });
        Instant now = clock.instant();
        AnnotationImport rater1Basis = null;
        AnnotationImport rater2Basis = null;
        if (slot == AnnotationSlot.ADJUDICATED) {
            rater1Basis = requireCompleteRater(batchId, AnnotationSlot.RATER_1, items);
            rater2Basis = requireCompleteRater(batchId, AnnotationSlot.RATER_2, items);
        }
        // Superar y volcar la vigente ANTES de insertar la nueva: uk_ann_import_current no se viola ni
        // transitoriamente (Hibernate ordena inserts antes de updates dentro de un mismo flush).
        supersedeCurrent(batchId, slot, now);
        int version = importRepository.findFirstByBatchIdAndSlotOrderByVersionDesc(batchId, slot)
                .map(previous -> previous.getVersion() + 1)
                .orElse(1);
        AnnotationImport imported = slot == AnnotationSlot.ADJUDICATED
                ? AnnotationImport.adjudicated(batch, version, raterName, hash, text, researcher, now, rater1Basis, rater2Basis)
                : new AnnotationImport(batch, slot, version, raterName, hash, text, researcher, now);
        try {
            imported = importRepository.saveAndFlush(imported);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, IMPORT_VERSION_CONSTRAINT) || violates(e, IMPORT_CURRENT_CONSTRAINT)) {
                throw new ConflictException("Another import for slot " + slot + " was recorded at the same time, retry");
            }
            throw e;
        }
        items.forEach(item -> item.record(slot, scores.get(item.getSampleCode())));
        if (slot != AnnotationSlot.ADJUDICATED) {
            invalidateAdjudication(batchId, items, now, researcher, study, slot);
        }
        itemRepository.saveAll(items);
        audit(researcher, study, "ANNOTATION_IMPORTED", imported.getId(),
                "kind=" + batch.getKind() + ", slot=" + slot + ", version=" + version
                        + ", sha256=" + hash + ", rater=" + raterName);
        return summarize(batch, items);
    }

    private static String requireRater(String rater) {
        if (rater == null || rater.isBlank() || rater.strip().length() > 80) {
            throw new BusinessException("Rater name is required (at most 80 characters)");
        }
        return rater.strip();
    }

    private void requireDistinctRater(UUID batchId, AnnotationSlot slot, String rater) {
        AnnotationSlot other = switch (slot) {
            case RATER_1 -> AnnotationSlot.RATER_2;
            case RATER_2 -> AnnotationSlot.RATER_1;
            case ADJUDICATED -> null;
        };
        if (other == null) {
            return;
        }
        importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(batchId, other)
                .filter(current -> current.getRater().equalsIgnoreCase(rater))
                .ifPresent(current -> {
                    throw new BusinessException("RATER_1 and RATER_2 must be distinct raters");
                });
    }

    /** Importacion vigente de la ranura de evaluador, exigiendo que todas las filas tengan su puntaje. */
    private AnnotationImport requireCompleteRater(UUID batchId, AnnotationSlot slot, List<AnnotationItem> items) {
        return importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(batchId, slot)
                .filter(current -> items.stream().allMatch(item -> item.score(slot) != null))
                .orElseThrow(() -> new BusinessException("Adjudication requires two complete rater imports"));
    }

    private void supersedeCurrent(UUID batchId, AnnotationSlot slot, Instant now) {
        importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(batchId, slot).ifPresent(previous -> {
            previous.supersede(now);
            importRepository.saveAndFlush(previous);
        });
    }

    /** Un evaluador revisado deja sin base al consenso: se supera la adjudicacion y se vacian sus puntajes. */
    private void invalidateAdjudication(
            UUID batchId, List<AnnotationItem> items, Instant now, Researcher researcher, ResearchStudy study,
            AnnotationSlot revisedSlot) {
        importRepository.findFirstByBatchIdAndSlotAndSupersededAtIsNull(batchId, AnnotationSlot.ADJUDICATED)
                .ifPresent(adjudication -> {
                    adjudication.supersede(now);
                    importRepository.saveAndFlush(adjudication);
                    items.forEach(AnnotationItem::clearAdjudicatedScore);
                    audit(researcher, study, "ANNOTATION_ADJUDICATION_INVALIDATED", adjudication.getId(),
                            "version=" + adjudication.getVersion() + ", revisedSlot=" + revisedSlot);
                });
    }

    /** Puntaje por codigo de muestra; falla ante cabecera, codigos, cobertura o valores invalidos. */
    private static Map<String, Integer> parseScores(AnnotationKind kind, List<List<String>> rows, List<AnnotationItem> items) {
        if (rows.isEmpty()) {
            throw new BusinessException("File is empty");
        }
        List<String> header = rows.get(0).stream().map(String::strip).toList();
        if (!header.equals(IMPORT_HEADER) && !header.equals(kind.columns())) {
            throw new BusinessException("Unexpected header: expected " + String.join(",", IMPORT_HEADER)
                    + " or " + String.join(",", kind.columns()));
        }
        if (rows.size() < 2) {
            throw new BusinessException("File has a header but no rows");
        }
        int scoreColumn = header.size() - 1;
        Map<String, AnnotationItem> byCode = items.stream()
                .collect(Collectors.toMap(AnnotationItem::getSampleCode, item -> item));
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != header.size()) {
                throw new BusinessException("Row " + (i + 1) + " has " + row.size() + " columns, expected " + header.size());
            }
            String code = row.get(0).strip();
            AnnotationItem item = byCode.get(code);
            if (item == null) {
                throw new BusinessException("Unknown sample code in row " + (i + 1) + ": " + code);
            }
            if (scores.containsKey(code)) {
                throw new BusinessException("Duplicate sample code in row " + (i + 1) + ": " + code);
            }
            scores.put(code, parseScore(kind, item, row.get(scoreColumn)));
        }
        List<String> missing = items.stream()
                .map(AnnotationItem::getSampleCode)
                .filter(code -> !scores.containsKey(code))
                .toList();
        if (!missing.isEmpty()) {
            String listed = missing.stream().limit(MAX_LISTED_CODES).collect(Collectors.joining(", "));
            throw new BusinessException("Missing scores for " + missing.size() + " sample(s): " + listed
                    + (missing.size() > MAX_LISTED_CODES ? ", ..." : ""));
        }
        return scores;
    }

    private static int parseScore(AnnotationKind kind, AnnotationItem item, String raw) {
        String code = item.getSampleCode();
        String value = raw.strip();
        if (value.isEmpty()) {
            throw new BusinessException("Missing score for sample " + code);
        }
        if (!SCORE.matcher(value).matches() || !kind.accepts(Integer.parseInt(value))) {
            throw new BusinessException("Score for sample " + code + " must be " + kind.scoreRule() + ", got '" + value + "'");
        }
        int score = Integer.parseInt(value);
        if (kind == AnnotationKind.ORTHOGRAPHY && score > wordCount(item.getRun().getFinalText())) {
            throw new BusinessException("Score exceeds the word count of sample " + code);
        }
        return score;
    }

    /** Palabras del texto evaluado: cota superior del conteo ortografico, con la misma tokenizacion que PEO. */
    static int wordCount(String text) {
        return StudyMetricsService.wordCount(text);
    }

    // ----------------------------------------------------------------- summary

    private AnnotationBatchResponse summarize(AnnotationBatch batch, List<AnnotationItem> items) {
        List<AnnotationSlot> completed = new ArrayList<>();
        for (AnnotationSlot slot : AnnotationSlot.values()) {
            if (!items.isEmpty() && items.stream().allMatch(item -> item.score(slot) != null)) {
                completed.add(slot);
            }
        }
        AgreementSummary agreement = completed.contains(AnnotationSlot.RATER_1) && completed.contains(AnnotationSlot.RATER_2)
                ? InterRaterAgreement.of(scores(items, AnnotationSlot.RATER_1), scores(items, AnnotationSlot.RATER_2))
                : AgreementSummary.incomplete();
        List<AnnotationImport> imports = importRepository.findByBatchIdOrderByVersionAsc(batch.getId());
        return AnnotationBatchResponse.from(batch, completed, imports, agreement, adjudicationCurrent(imports));
    }

    /** True solo si la adjudicacion vigente se resolvio sobre las importaciones de evaluador vigentes. */
    private static boolean adjudicationCurrent(List<AnnotationImport> imports) {
        AnnotationImport rater1 = current(imports, AnnotationSlot.RATER_1);
        AnnotationImport rater2 = current(imports, AnnotationSlot.RATER_2);
        AnnotationImport adjudication = current(imports, AnnotationSlot.ADJUDICATED);
        return adjudication != null && adjudication.adjudicates(rater1, rater2);
    }

    private static AnnotationImport current(List<AnnotationImport> imports, AnnotationSlot slot) {
        return imports.stream().filter(i -> i.getSlot() == slot && i.isCurrent()).findFirst().orElse(null);
    }

    private static List<Integer> scores(List<AnnotationItem> items, AnnotationSlot slot) {
        return items.stream().map(item -> item.score(slot)).toList();
    }

    // ----------------------------------------------------------------- helpers

    /** Un estudio ajeno responde igual que uno inexistente para no permitir enumerarlos. */
    private ResearchStudy requireOwnedStudy(UUID researcherId, UUID studyId) {
        return studyRepository.findByIdAndCreatedById(studyId, researcherId)
                .orElseThrow(() -> new NotFoundException("Study not found"));
    }

    private AnnotationBatch requireBatch(UUID studyId, UUID batchId) {
        return batchRepository.findByIdAndStudyId(batchId, studyId)
                .orElseThrow(() -> new NotFoundException("Annotation batch not found"));
    }

    private AnnotationBatch requireBatchForUpdate(UUID studyId, UUID batchId) {
        return batchRepository.findByIdAndStudyIdForUpdate(batchId, studyId)
                .orElseThrow(() -> new NotFoundException("Annotation batch not found"));
    }

    private Researcher requireResearcher(UUID researcherId) {
        return researcherRepository.findById(researcherId)
                .orElseThrow(() -> new NotFoundException("Researcher not found"));
    }

    private void audit(Researcher researcher, ResearchStudy study, String action, UUID targetId, String detail) {
        auditRepository.save(new ResearchAuditEvent(researcher, study, action, targetId, detail));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
