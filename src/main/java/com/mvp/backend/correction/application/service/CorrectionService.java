package com.mvp.backend.correction.application.service;

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
import com.mvp.backend.sentencetest.application.service.StudentTestService;
import com.mvp.backend.sentencetest.domain.model.AutoErrorDetail;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.shared.dto.PagedResponse;
import com.mvp.backend.shared.exception.AiServiceException;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

@Service
public class CorrectionService {

    private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);
    static final String INCIDENT_MODEL_VERSION_CHANGED = "MODEL_VERSION_CHANGED";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final StudentRepository studentRepository;
    private final CorrectionSessionRepository sessionRepository;
    private final WordCorrectionRepository wordCorrectionRepository;
    private final AiCorrectionClient aiCorrectionClient;
    private final StudentTestService studentTestService;
    private final TestAttemptRepository attemptRepository;
    private final TransactionTemplate readOnlyTransaction;
    private final TransactionTemplate writeTransaction;
    private final ObjectMapper objectMapper;

    public CorrectionService(
            StudentRepository studentRepository,
            CorrectionSessionRepository sessionRepository,
            WordCorrectionRepository wordCorrectionRepository,
            AiCorrectionClient aiCorrectionClient,
            StudentTestService studentTestService,
            TestAttemptRepository attemptRepository,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.studentRepository = studentRepository;
        this.sessionRepository = sessionRepository;
        this.wordCorrectionRepository = wordCorrectionRepository;
        this.aiCorrectionClient = aiCorrectionClient;
        this.studentTestService = studentTestService;
        this.attemptRepository = attemptRepository;
        // process() corre en dos fases con la llamada a la IA fuera de toda transaccion (ver process).
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * Correccion en dos fases para que ninguna transaccion abarque la llamada a la IA y el intento de
     * prueba solo se modifique bajo su bloqueo de fila:
     * <ol>
     * <li>Fase A (solo lectura): validar alumno y, si hay {@code id_respuesta}, que la oracion sea con
     * ayuda y este abierta; sin {@code id_respuesta} no puede haber un intento en curso. Nada se persiste.</li>
     * <li>Llamada a la IA sin transaccion.</li>
     * <li>Fase B (escritura): bloquea el intento, relee la respuesta y revalida; si la oracion se termino
     * mientras la IA respondia, no se guarda nada. Luego guarda la sesion y la version del modelo.</li>
     * </ol>
     */
    public CorrectionSessionResponse process(UUID studentId, ProcessCorrectionRequest request) {
        Preflight preflight = readOnlyTransaction.execute(status -> preflight(studentId, request.testResponseId()));
        var aiResponse = requestCorrection(request.originalText(), studentId);
        return writeTransaction.execute(status -> persistCorrection(preflight, request.originalText(), aiResponse));
    }

    /** Resultado de la fase de lectura: solo identificadores, ninguna entidad sale de la transaccion. */
    private record Preflight(UUID studentId, UUID responseId, UUID attemptId) {
    }

    private Preflight preflight(UUID studentId, UUID responseId) {
        requireStudent(studentId);
        if (responseId == null) {
            // Con una prueba en curso toda correccion debe venir ligada a su oracion: si no, el conteo
            // de sugerencias y la version del modelo quedarian fuera del intento.
            if (studentTestService.activeAttempt(studentId).isPresent()) {
                throw new BusinessException("A sentence test is in progress");
            }
            return new Preflight(studentId, null, null);
        }
        TestResponse response = studentTestService.responseForCorrection(studentId, responseId);
        requireAssistedAndOpen(response);
        return new Preflight(studentId, responseId, response.getAttempt().getId());
    }

    private static void requireAssistedAndOpen(TestResponse response) {
        // La condicion se verifica antes de tocar la IA o persistir nada: sin ayuda el backend
        // bloquea por su cuenta, independientemente de lo que haga el teclado.
        if (!response.getSentence().isAssisted()) {
            throw new BusinessException("Contextual correction is disabled for this sentence");
        }
        if (!response.acceptsCorrections()) {
            throw new ConflictException("Sentence is not open");
        }
    }

    private AiCorrectionResponse requestCorrection(String originalText, UUID studentId) {
        var aiResponse = aiCorrectionClient.correct(originalText, studentId);
        if (aiResponse == null) {
            throw new AiServiceException("AI correction service returned an empty response", null);
        }
        return aiResponse;
    }

    private CorrectionSessionResponse persistCorrection(Preflight preflight, String originalText, AiCorrectionResponse aiResponse) {
        TestAttempt attempt = null;
        TestResponse response = null;
        if (preflight.responseId() != null) {
            // Mismo bloqueo que Comenzar/Terminar: la version del modelo y las incidencias del intento
            // se escriben en serie con el resto de transiciones del intento.
            attempt = attemptRepository.findByIdForUpdate(preflight.attemptId())
                    .orElseThrow(() -> new NotFoundException("Attempt not found"));
            response = studentTestService.responseForCorrection(preflight.studentId(), preflight.responseId());
            // Mientras la IA respondia, el telefono pudo terminar la oracion: la correccion llega tarde
            // y no debe dejar rastro (el teclado muestra "texto cambiado").
            requireAssistedAndOpen(response);
        }
        var student = requireStudent(preflight.studentId());
        var session = sessionRepository.save(new CorrectionSession(student, originalText, response));
        if (attempt != null && !attempt.recordModelVersion(aiResponse.modelVersion())) {
            // El intento ya fue atendido por otra version del modelo: se audita en el intento y en la
            // respuesta, no se oculta ni se rechaza.
            attempt.recordIncident();
            response.appendDetail(AutoErrorDetail.withIncident(response.getAutoErrorDetail(), INCIDENT_MODEL_VERSION_CHANGED));
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
        // Una sesion ligada a una oracion solo admite feedback mientras la oracion sigue abierta: al
        // terminarla, la aceptacion queda fija (los contadores de la respuesta ya se enviaron con Terminar).
        if (session.isInTest() && session.getTestResponse().isFinished()) {
            throw new BusinessException("Feedback is closed for this sentence");
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
        // El feedback de una sesion de prueba se guarda pero no se reenvia: el LoRA es global y las
        // decisiones tomadas durante una prueba no deben alterar el comportamiento del modelo en caliente.
        if (!session.isInTest()) {
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
