package com.mvp.backend.correction.application.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.correction.application.dto.CorrectionFeedbackRequest;
import com.mvp.backend.correction.application.dto.CorrectionSessionResponse;
import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.application.dto.WordCorrectionResponse;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.model.WordCorrection;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.experiment.application.service.ExperimentIncidentRecorder;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.shared.dto.PagedResponse;
import com.mvp.backend.shared.exception.AiServiceException;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

@Service
public class CorrectionService {

    private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);
    static final String INCIDENT_AI_REQUEST_FAILED = "AI_REQUEST_FAILED";
    static final String INCIDENT_MODEL_VERSION_CHANGED = "MODEL_VERSION_CHANGED";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final StudentRepository studentRepository;
    private final CorrectionSessionRepository sessionRepository;
    private final WordCorrectionRepository wordCorrectionRepository;
    private final AiCorrectionClient aiCorrectionClient;
    private final ExperimentRunRepository runRepository;
    private final ExperimentIncidentRecorder incidentRecorder;
    private final TransactionTemplate readOnlyTransaction;
    private final TransactionTemplate writeTransaction;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CorrectionService(
            StudentRepository studentRepository,
            CorrectionSessionRepository sessionRepository,
            WordCorrectionRepository wordCorrectionRepository,
            AiCorrectionClient aiCorrectionClient,
            ExperimentRunRepository runRepository,
            ExperimentIncidentRecorder incidentRecorder,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Clock clock) {
        this.studentRepository = studentRepository;
        this.sessionRepository = sessionRepository;
        this.wordCorrectionRepository = wordCorrectionRepository;
        this.aiCorrectionClient = aiCorrectionClient;
        this.runRepository = runRepository;
        this.incidentRecorder = incidentRecorder;
        // process() corre en dos fases con la llamada a la IA fuera de toda transaccion (ver process).
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Correccion en dos fases para que ninguna transaccion abarque la llamada a la IA y la ejecucion
     * experimental solo se modifique bajo su bloqueo de fila:
     * <ol>
     * <li>Fase A (solo lectura): validar alumno y condicion de la ejecucion; nada se persiste.</li>
     * <li>Llamada a la IA sin transaccion; un fallo se registra como incidencia en transaccion propia.</li>
     * <li>Fase B (escritura): relee la ejecucion con bloqueo y revalida; si dejo de estar activa
     * mientras la IA respondia, no se guarda nada. Luego guarda la sesion y la version del modelo.</li>
     * </ol>
     */
    public CorrectionSessionResponse process(UUID studentId, ProcessCorrectionRequest request) {
        Preflight preflight = readOnlyTransaction.execute(status -> preflight(studentId, request.experimentRunId()));
        var aiResponse = requestCorrection(request.originalText(), studentId, preflight.runId());
        return writeTransaction.execute(status -> persistCorrection(preflight, request.originalText(), aiResponse));
    }

    /** Resultado de la fase de lectura: solo identificadores, ninguna entidad sale de la transaccion. */
    private record Preflight(UUID studentId, UUID runId) {
    }

    private Preflight preflight(UUID studentId, UUID runId) {
        // La condicion se verifica antes de tocar la IA o persistir nada: en UNASSISTED el backend
        // bloquea por su cuenta, independientemente de lo que haga el teclado.
        if (runId != null) {
            var run = runRepository.findByIdAndParticipantStudentId(runId, studentId)
                    .orElseThrow(() -> new NotFoundException("Experiment run not found"));
            requireAssistedAndActive(run, "Experiment run is not active");
        }
        requireStudent(studentId);
        return new Preflight(studentId, runId);
    }

    private static void requireAssistedAndActive(ExperimentRun run, String inactiveMessage) {
        if (!run.isActive()) {
            throw new BusinessException(inactiveMessage);
        }
        if (run.getCondition() == ExperimentCondition.UNASSISTED) {
            throw new BusinessException("Contextual correction is disabled for this experiment run");
        }
    }

    private AiCorrectionResponse requestCorrection(String originalText, UUID studentId, UUID runId) {
        try {
            var aiResponse = aiCorrectionClient.correct(originalText, studentId);
            if (aiResponse == null) {
                throw new AiServiceException("AI correction service returned an empty response", null);
            }
            return aiResponse;
        } catch (AiServiceException exception) {
            if (runId != null) {
                // Transaccion propia (aqui no hay ninguna abierta ni bloqueo tomado): la incidencia queda.
                incidentRecorder.record(runId, INCIDENT_AI_REQUEST_FAILED);
            }
            throw exception;
        }
    }

    private CorrectionSessionResponse persistCorrection(Preflight preflight, String originalText, AiCorrectionResponse aiResponse) {
        ExperimentRun run = null;
        if (preflight.runId() != null) {
            run = runRepository.findByIdAndParticipantStudentIdForUpdate(preflight.runId(), preflight.studentId())
                    .orElseThrow(() -> new NotFoundException("Experiment run not found"));
            // Mientras la IA respondia, el telefono pudo completar o cancelar la ejecucion: la
            // correccion llega tarde y no debe dejar rastro (el teclado muestra "texto cambiado").
            requireAssistedAndActive(run, "Experiment run is no longer active");
        }
        var student = requireStudent(preflight.studentId());
        var session = sessionRepository.save(new CorrectionSession(student, originalText, run));
        if (run != null && !run.recordModelVersion(aiResponse.modelVersion())) {
            // La ejecucion ya fue atendida por otra version del modelo: se audita, no se oculta ni se
            // rechaza. Directamente sobre la entidad bloqueada (el recorder REQUIRES_NEW esperaria
            // por este mismo bloqueo).
            run.recordIncident(INCIDENT_MODEL_VERSION_CHANGED, clock.instant());
        }
        List<String> suggestions = normalizeSuggestions(aiResponse.correctedText(), aiResponse.suggestions());
        // El detalle palabra por palabra ya no lo entrega la IA: se derivara por diff
        // contra la sugerencia aceptada en registerFeedback (ver docs/arquitectura-integracion.md).
        session.complete(
                aiResponse.correctedText(),
                0,
                writeSuggestions(suggestions),
                aiResponse.processingTimeMs());
        return toResponse(session, List.of());
    }

    @Transactional
    public CorrectionSessionResponse registerFeedback(UUID studentId, UUID sessionId, CorrectionFeedbackRequest request) {
        requireStudent(studentId);
        var session = sessionRepository.findByIdAndStudentIdForUpdate(sessionId, studentId)
                .orElseThrow(() -> new NotFoundException("Correction session not found"));
        // Una sesion experimental solo admite feedback mientras su ejecucion sigue ACTIVE: al terminar la
        // ejecucion, la aceptacion queda fija (los lotes semanticos la congelan y TAS aceptada depende de ella).
        if (session.isExperimental() && !session.getExperimentRun().isActive()) {
            throw new BusinessException("Feedback is closed for this experiment run");
        }
        // Valores efectivos: deshacer una sugerencia aplicada equivale a rechazarla, envie lo que envie el flag.
        boolean accepted = request.effectiveAccepted();
        String reason = request.effectiveReason();
        String selectedSuggestion = request.isUndo() ? null : validateSelectedSuggestion(session, request);
        String finalText = emptyToNull(request.finalText());

        // Regla de transicion: tras un UNDO el texto corregido ya no esta aplicado; volver a
        // "aceptar" seria un reenvio tardio o un teclado desincronizado, nunca una decision nueva.
        if (CorrectionFeedbackRequest.REASON_UNDO.equals(session.getFeedbackReason()) && accepted) {
            throw new BusinessException("Feedback cannot re-accept a corrected text after undo");
        }
        // Reintento identico (misma decision, misma sugerencia, mismo texto, mismo motivo): no se reescribe nada.
        if (Objects.equals(session.getAcceptedCorrection(), accepted)
                && Objects.equals(session.getSelectedSuggestion(), selectedSuggestion)
                && Objects.equals(session.getFinalText(), finalText)
                && Objects.equals(session.getFeedbackReason(), reason)) {
            return toResponse(session, wordCorrectionRepository.findByCorrectionSessionIdOrderByStartPosition(sessionId));
        }

        // Texto que el alumno realmente validó: su edición si la hay, si no la sugerencia base.
        String acceptedText = finalText != null ? finalText : selectedSuggestion;
        // El feedback puede cambiar (aceptar y luego deshacer): recalculamos siempre desde cero.
        wordCorrectionRepository.deleteByCorrectionSessionId(sessionId);
        List<WordCorrection> wordCorrections = accepted && acceptedText != null
                ? deriveWordCorrections(session, acceptedText)
                : List.of();
        session.registerFeedback(selectedSuggestion, finalText, accepted, wordCorrections.size(), reason);
        // Se reenvía a la IA el texto validado a mano (texto_final si existe) para su aprendizaje (best-effort).
        // El feedback de una sesion experimental se guarda pero no se reenvia: el LoRA es global y las
        // decisiones del experimento no deben alterar el comportamiento del modelo en caliente.
        if (!session.isExperimental()) {
            forwardAfterCommit(studentId, session.getOriginalText(), acceptedText, accepted);
        }
        return toResponse(session, wordCorrections);
    }

    /**
     * El reenvio a la IA ocurre tras confirmar la transaccion, fuera del bloqueo de la sesion: una IA lenta
     * (cold start) no retiene la fila ni la conexion, y un fallo del reenvio nunca deshace el feedback ya
     * guardado. Sin transaccion activa (pruebas unitarias) se reenvia en linea.
     */
    private void forwardAfterCommit(UUID studentId, String originalText, String acceptedText, boolean accepted) {
        Runnable forward = () -> {
            try {
                aiCorrectionClient.sendFeedback(studentId, originalText, acceptedText, accepted);
            } catch (RuntimeException exception) {
                // Best-effort: solo la clase, nunca el mensaje (podria contener el texto del alumno).
                log.warn("Could not forward correction feedback to the AI service ({})", exception.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forward.run();
                }
            });
        } else {
            forward.run();
        }
    }

    private List<WordCorrection> deriveWordCorrections(CorrectionSession session, String acceptedSuggestion) {
        // La IA ya no entrega el detalle palabra por palabra: se deriva por diff
        // entre el texto original y la sugerencia aceptada.
        List<WordCorrection> corrections = WordCorrectionDiff.between(session.getOriginalText(), acceptedSuggestion)
                .stream()
                .map(change -> new WordCorrection(
                        session,
                        change.originalWord(),
                        change.correctedWord(),
                        change.startPosition(),
                        change.endPosition()))
                .toList();
        return corrections.isEmpty() ? List.of() : wordCorrectionRepository.saveAll(corrections);
    }

    @Transactional(readOnly = true)
    public List<WordCorrectionResponse> getWords(UUID studentId, UUID sessionId) {
        requireStudent(studentId);
        findOwnedSession(studentId, sessionId);
        return wordCorrectionRepository.findByCorrectionSessionIdOrderByStartPosition(sessionId).stream()
                .map(WordCorrectionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<CorrectionSessionResponse> getSessions(UUID studentId, Pageable pageable) {
        requireStudent(studentId);
        Pageable limited = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 50), pageable.getSort());
        return PagedResponse.from(sessionRepository.findByStudentIdOrderByCreatedAtDesc(studentId, limited)
                .map(session -> toResponse(session, List.of())));
    }

    private CorrectionSession findOwnedSession(UUID studentId, UUID sessionId) {
        return sessionRepository.findByIdAndStudentId(sessionId, studentId)
                .orElseThrow(() -> new NotFoundException("Correction session not found"));
    }

    private Student requireStudent(UUID studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new NotFoundException("Student not found"));
    }

    private CorrectionSessionResponse toResponse(CorrectionSession session, List<WordCorrection> corrections) {
        List<String> suggestions = normalizeSuggestions(session.getCorrectedText(), readSuggestions(session.getSuggestionsJson()));
        return CorrectionSessionResponse.from(
                session,
                suggestions,
                corrections.stream().map(WordCorrectionResponse::from).toList());
    }

    private String validateSelectedSuggestion(CorrectionSession session, CorrectionFeedbackRequest request) {
        String selectedSuggestion = emptyToNull(request.selectedSuggestion());
        if (request.effectiveAccepted() && selectedSuggestion == null) {
            throw new BusinessException("Accepted correction requires a selected suggestion");
        }
        if (selectedSuggestion == null) {
            return null;
        }
        List<String> suggestions = normalizeSuggestions(session.getCorrectedText(), readSuggestions(session.getSuggestionsJson()));
        if (!suggestions.contains(selectedSuggestion)) {
            throw new BusinessException("Selected suggestion was not offered for this correction session");
        }
        return selectedSuggestion;
    }

    private List<String> normalizeSuggestions(String correctedText, List<String> suggestions) {
        return OfferedSuggestions.of(correctedText, suggestions);
    }

    private String emptyToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }

    private String writeSuggestions(List<String> suggestions) {
        try {
            return objectMapper.writeValueAsString(suggestions);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize suggestions", exception);
        }
    }

    private List<String> readSuggestions(String suggestionsJson) {
        if (suggestionsJson == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(suggestionsJson, STRING_LIST);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not deserialize suggestions", exception);
        }
    }
}
