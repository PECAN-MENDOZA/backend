package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.correction.application.dto.CorrectionFeedbackRequest;
import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.sentencetest.application.service.StudentTestService;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Reglas de la correccion ligada a una oracion de prueba (spec §1): sin id_respuesta con intento en
 * curso, oracion sin ayuda, sesion ligada a la respuesta, version del modelo congelada en el intento y
 * feedback cerrado al terminar la oracion.
 */
@ExtendWith(MockitoExtension.class)
class CorrectionSentenceGuardTests {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private CorrectionSessionRepository sessionRepository;

    @Mock
    private WordCorrectionRepository wordCorrectionRepository;

    @Mock
    private AiCorrectionClient aiCorrectionClient;

    @Mock
    private StudentTestService studentTestService;

    @Mock
    private TestAttemptRepository attemptRepository;

    private CorrectionService correctionService;

    private Student student;
    private SentenceTest test;
    private TestAttempt attempt;

    @BeforeEach
    void setUp() {
        correctionService = new CorrectionService(
                studentRepository,
                sessionRepository,
                wordCorrectionRepository,
                aiCorrectionClient,
                studentTestService,
                attemptRepository,
                new NoOpTransactionManager(),
                new ObjectMapper());
        student = new Student("alumno_prueba", "UPC", "encoded-password");
        test = new SentenceTest("PRUEBA-01", "Dictado", UUID.randomUUID());
        attempt = new TestAttempt(test, student, "app-1.0", "backend-1.0", NOW);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
    }

    @Test
    void correctionWithoutResponseIdIsRejectedWhileATestIsInProgress() {
        when(studentTestService.activeAttempt(student.getId())).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> correctionService.process(student.getId(), new ProcessCorrectionRequest("texto")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A sentence test is in progress");
        verifyNoInteractions(aiCorrectionClient);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void unassistedSentenceRejectsContextualCorrection() {
        TestResponse response = openResponse(1, Assistance.UNASSISTED);
        when(studentTestService.responseForCorrection(student.getId(), response.getId())).thenReturn(response);

        assertThatThrownBy(() -> correctionService.process(
                student.getId(), new ProcessCorrectionRequest("texto", response.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Contextual correction is disabled for this sentence");
        verifyNoInteractions(aiCorrectionClient);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void finishedSentenceIsNotOpenForCorrections() {
        TestResponse response = openResponse(1, Assistance.ASSISTED);
        response.finish("texto", 100L, 5_000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), NOW.plusSeconds(5));
        when(studentTestService.responseForCorrection(student.getId(), response.getId())).thenReturn(response);

        assertThatThrownBy(() -> correctionService.process(
                student.getId(), new ProcessCorrectionRequest("texto", response.getId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Sentence is not open");
        verifyNoInteractions(aiCorrectionClient);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void assistedOpenSentenceLinksTheSessionAndFreezesTheModelVersion() {
        TestResponse response = openResponse(1, Assistance.ASSISTED);
        when(studentTestService.responseForCorrection(student.getId(), response.getId())).thenReturn(response);
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(sessionRepository.save(any(CorrectionSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiCorrectionClient.correct("texto", student.getId())).thenReturn(aiResponse("texto", "beto-lora-1.2"));

        var result = correctionService.process(
                student.getId(), new ProcessCorrectionRequest("texto", response.getId()));

        assertThat(result.correctedText()).isEqualTo("texto");
        verify(sessionRepository).save(argThat(session -> session.isInTest() && session.getTestResponse() == response));
        assertThat(attempt.getModelVersion()).isEqualTo("beto-lora-1.2");
        assertThat(attempt.getIncidentCount()).isZero();
        assertThat(response.getAutoErrorDetail()).isNull();
    }

    @Test
    void differingModelVersionRecordsAnIncidentOnTheAttemptAndTheResponse() {
        TestResponse response = openResponse(1, Assistance.ASSISTED);
        when(studentTestService.responseForCorrection(student.getId(), response.getId())).thenReturn(response);
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(sessionRepository.save(any(CorrectionSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiCorrectionClient.correct("texto uno", student.getId())).thenReturn(aiResponse("texto uno", "m1"));
        when(aiCorrectionClient.correct("texto dos", student.getId())).thenReturn(aiResponse("texto dos", "m2"));

        correctionService.process(student.getId(), new ProcessCorrectionRequest("texto uno", response.getId()));
        correctionService.process(student.getId(), new ProcessCorrectionRequest("texto dos", response.getId()));

        assertThat(attempt.getModelVersion()).isEqualTo("m1");
        assertThat(attempt.getIncidentCount()).isEqualTo(1);
        assertThat(response.getAutoErrorDetail()).isEqualTo("{\"incidents\":[\"MODEL_VERSION_CHANGED\"]}");
    }

    @Test
    void sentenceFinishedDuringTheAiCallIsRejectedWithoutPersistingAnything() {
        TestResponse response = openResponse(1, Assistance.ASSISTED);
        when(studentTestService.responseForCorrection(student.getId(), response.getId())).thenReturn(response);
        // Entre la lectura sin bloqueo y la fase de escritura, el telefono termino la oracion.
        when(attemptRepository.findByIdForUpdate(attempt.getId())).thenAnswer(invocation -> {
            response.finish("texto", 100L, 5_000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), NOW.plusSeconds(5));
            return Optional.of(attempt);
        });
        when(aiCorrectionClient.correct("texto", student.getId())).thenReturn(aiResponse("texto", "beto-lora-1.2"));

        assertThatThrownBy(() -> correctionService.process(
                student.getId(), new ProcessCorrectionRequest("texto", response.getId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Sentence is not open");
        verify(sessionRepository, never()).save(any());
        assertThat(attempt.getModelVersion()).isNull();
        assertThat(attempt.getIncidentCount()).isZero();
    }

    @Test
    void feedbackOfATestSessionIsStoredButNotForwardedToTheAi() {
        TestResponse response = openResponse(1, Assistance.ASSISTED);
        var session = new CorrectionSession(student, "el nino iva", response);
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));
        when(wordCorrectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = correctionService.registerFeedback(
                student.getId(), session.getId(), new CorrectionFeedbackRequest("el nino iba", true, null));

        assertThat(result.acceptedCorrection()).isTrue();
        assertThat(result.correctionsCount()).isEqualTo(1);
        verify(aiCorrectionClient, never()).sendFeedback(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void feedbackIsClosedOnceTheSentenceIsFinished() {
        TestResponse response = openResponse(1, Assistance.ASSISTED);
        var session = new CorrectionSession(student, "el nino iva", response);
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        response.finish("el nino iba", 100L, 5_000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), NOW.plusSeconds(5));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(), session.getId(), new CorrectionFeedbackRequest("el nino iba", true, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Feedback is closed for this sentence");
        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(), session.getId(), new CorrectionFeedbackRequest("el nino iba", false, null, "UNDO")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Feedback is closed for this sentence");

        assertThat(session.getAcceptedCorrection()).isNull();
        assertThat(session.getFeedbackReason()).isNull();
        verifyNoInteractions(wordCorrectionRepository);
        verify(aiCorrectionClient, never()).sendFeedback(any(), anyString(), any(), anyBoolean());
    }

    /** Respuesta comenzada (Comenzar pulsado) y todavia no terminada. */
    private TestResponse openResponse(int position, Assistance assistance) {
        var sentence = new TestSentence(test, position, SentenceKind.DICTATED, "El nino iba al patio.", assistance);
        return new TestResponse(attempt, sentence, NOW);
    }

    private AiCorrectionResponse aiResponse(String correctedText, String modelVersion) {
        return new AiCorrectionResponse(student.getId(), correctedText, 50, List.of(correctedText), modelVersion);
    }

    /** Ejecuta los callbacks de TransactionTemplate en linea, sin base de datos. */
    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
