package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.application.dto.CorrectionFeedbackRequest;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.sentencetest.application.service.StudentTestService;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.shared.exception.AiServiceException;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

@ExtendWith(MockitoExtension.class)
class CorrectionServiceTests {

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
        student = readyStudent("student_exp");
    }

    @Test
    void persistsAiCorrectionSuggestions() {
        var student = readyStudent("student_01");
        var aiResponse = new AiCorrectionResponse(
                student.getId(),
                "los ninos fueron al patio",
                267,
                List.of("los ninos fueron al patio"),
                null);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.save(any(CorrectionSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiCorrectionClient.correct("los ninos fueron al patio", student.getId())).thenReturn(aiResponse);

        var response = correctionService.process(
                student.getId(),
                new ProcessCorrectionRequest("los ninos fueron al patio"));

        assertThat(response.correctedText()).isEqualTo("los ninos fueron al patio");
        assertThat(response.suggestions()).containsExactly("los ninos fueron al patio");
        assertThat(response.suggestionOptions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.text()).isEqualTo("los ninos fueron al patio");
            assertThat(suggestion.recommended()).isTrue();
        });
        assertThat(response.correctedWords()).isEmpty();
    }

    @Test
    void normalizesSuggestionOrderRemovesDuplicatesAndLimitsAlternatives() {
        var student = readyStudent("student_03");
        var aiResponse = new AiCorrectionResponse(
                student.getId(),
                "texto principal",
                120,
                List.of("texto alternativo", "texto principal", "texto alternativo", "tercera opcion", "cuarta opcion"),
                null);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.save(any(CorrectionSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiCorrectionClient.correct("texto original", student.getId())).thenReturn(aiResponse);

        var response = correctionService.process(student.getId(), new ProcessCorrectionRequest("texto original"));

        assertThat(response.suggestions()).containsExactly("texto principal", "texto alternativo", "tercera opcion");
        assertThat(response.suggestionOptions()).extracting(option -> option.recommended())
                .containsExactly(true, false, false);
    }

    @Test
    void acceptingFeedbackDerivesAndPersistsWordDiff() {
        var student = readyStudent("student_06");
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));
        when(wordCorrectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", true, null));

        assertThat(response.correctionsCount()).isEqualTo(1);
        assertThat(response.acceptedCorrection()).isTrue();
        assertThat(response.correctedWords()).singleElement().satisfies(word -> {
            assertThat(word.originalWord()).isEqualTo("iva");
            assertThat(word.correctedWord()).isEqualTo("iba");
            assertThat(word.startPosition()).isEqualTo(8);
            assertThat(word.endPosition()).isEqualTo(11);
        });
        verify(aiCorrectionClient).sendFeedback(student.getId(), "el nino iva", "el nino iba", true);
    }

    @Test
    void editingSuggestionUsesFinalTextForDiffAndAiLearning() {
        var student = readyStudent("student_08");
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));
        when(wordCorrectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Base = sugerencia ofrecida "el nino iba"; el alumno la edita a "el niño iba".
        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", true, "el niño iba"));

        assertThat(response.acceptedCorrection()).isTrue();
        assertThat(response.wasEdited()).isTrue();
        assertThat(response.finalText()).isEqualTo("el niño iba");
        // El diff se calcula contra el texto EDITADO (nino->niño ademas de iva->iba),
        // no contra la sugerencia base (que solo daria iva->iba).
        assertThat(response.correctedWords())
                .extracting(word -> word.originalWord() + "->" + word.correctedWord())
                .containsExactlyInAnyOrder("nino->niño", "iva->iba");
        // La IA aprende del texto editado.
        verify(aiCorrectionClient).sendFeedback(student.getId(), "el nino iva", "el niño iba", true);
    }

    @Test
    void ignoringFeedbackStoresNoWordCorrections() {
        var student = readyStudent("student_07");
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest(null, false, null));

        assertThat(response.acceptedCorrection()).isFalse();
        assertThat(response.correctionsCount()).isZero();
        assertThat(response.correctedWords()).isEmpty();
        verify(aiCorrectionClient).sendFeedback(student.getId(), "el nino iva", null, false);
    }

    @Test
    void rejectsAcceptedFeedbackWhenSuggestionWasNotOffered() {
        var student = readyStudent("student_04");
        var session = completedSession(student, "[\"opcion ofrecida\"]");
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("opcion inventada", true, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Selected suggestion was not offered for this correction session");
    }

    @Test
    void requiresSelectedSuggestionWhenFeedbackAcceptsCorrection() {
        var student = readyStudent("student_05");
        var session = completedSession(student, "[\"opcion ofrecida\"]");
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest(null, true, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Accepted correction requires a selected suggestion");
    }

    // --------------------------------------------------------- uso normal

    @Test
    void normalCorrectionStillWorksWithoutResponseId() {
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.save(any(CorrectionSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiCorrectionClient.correct("texto", student.getId())).thenReturn(aiResponse("texto", null));

        correctionService.process(student.getId(), new ProcessCorrectionRequest("texto"));

        verify(aiCorrectionClient).correct("texto", student.getId());
        verify(sessionRepository).save(argThat(session -> !session.isInTest()));
        verifyNoInteractions(attemptRepository);
    }

    @Test
    void aiFailurePersistsNothing() {
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(aiCorrectionClient.correct("texto", student.getId()))
                .thenThrow(new AiServiceException("unavailable", null));

        assertThatThrownBy(() -> correctionService.process(student.getId(), new ProcessCorrectionRequest("texto")))
                .isInstanceOf(AiServiceException.class);
        verify(sessionRepository, never()).save(any());
    }

    // ------------------------------------------------------------- feedback

    @Test
    void undoFeedbackPersistsTheReasonAndMarksTheCorrectionAsNotAccepted() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", false, null, "UNDO"));

        assertThat(response.acceptedCorrection()).isFalse();
        assertThat(response.correctionsCount()).isZero();
        assertThat(session.getFeedbackReason()).isEqualTo("UNDO");
        assertThat(session.getAcceptedCorrection()).isFalse();
        // El deshacer limpia cualquier diff previamente derivado.
        verify(wordCorrectionRepository).deleteByCorrectionSessionId(session.getId());
    }

    @Test
    void undoWithAFinalTextIsNotCountedAsAnEdit() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        // The keyboard reports the text left on screen after undoing; nothing was validated by the student.
        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", false, "el nino iva", "UNDO"));

        assertThat(response.acceptedCorrection()).isFalse();
        assertThat(response.wasEdited()).isFalse();
        assertThat(session.isWasEdited()).isFalse();
        assertThat(session.getFinalText()).isEqualTo("el nino iva");
    }

    @Test
    void rejectedFeedbackWithADifferentFinalTextIsNotAnEditEither() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(session.getStudent()));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest(null, false, "el nino ivaa"));

        assertThat(response.acceptedCorrection()).isFalse();
        assertThat(response.wasEdited()).isFalse();
    }

    @Test
    void feedbackIsSavedEvenWhenTheAiClientThrows() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));
        when(wordCorrectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(aiCorrectionClient).sendFeedback(any(), anyString(), any(), anyBoolean());

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", true, null));

        assertThat(response.acceptedCorrection()).isTrue();
        assertThat(session.getAcceptedCorrection()).isTrue();
        verify(aiCorrectionClient).sendFeedback(student.getId(), "el nino iva", "el nino iba", true);
    }

    @Test
    void undoReasonOverridesAnAcceptedFlagSentByMistake() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", true, null, "UNDO"));

        assertThat(response.acceptedCorrection()).isFalse();
        assertThat(response.correctedWords()).isEmpty();
        assertThat(session.getFeedbackReason()).isEqualTo("UNDO");
    }

    @Test
    void undoWithAcceptedTrueAndNoSuggestionIsCoercedToRejection() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        // Sin sugerencia y con el flag en true: en un UNDO el flag efectivo es false, asi que no se exige sugerencia.
        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest(null, true, null, "UNDO"));

        assertThat(response.acceptedCorrection()).isFalse();
        assertThat(response.correctedWords()).isEmpty();
        assertThat(session.getAcceptedCorrection()).isFalse();
        assertThat(session.getSelectedSuggestion()).isNull();
        assertThat(session.getFeedbackReason()).isEqualTo("UNDO");
    }

    @Test
    void lowercaseUndoIsStoredCanonically() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", true, null, " undo "));

        assertThat(response.acceptedCorrection()).isFalse();
        assertThat(session.getFeedbackReason()).isEqualTo("UNDO");
        assertThat(session.getSelectedSuggestion()).isNull();
    }

    @Test
    void reAcceptAfterUndoIsRejected() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        session.registerFeedback(null, null, false, 0, "UNDO");
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", true, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Feedback cannot re-accept a corrected text after undo");
        assertThat(session.getAcceptedCorrection()).isFalse();
        assertThat(session.getFeedbackReason()).isEqualTo("UNDO");
        verifyNoInteractions(wordCorrectionRepository);
        verify(aiCorrectionClient, never()).sendFeedback(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void identicalFeedbackRetryIsANoOp() {
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentIdForUpdate(session.getId(), student.getId())).thenReturn(Optional.of(session));
        when(wordCorrectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new CorrectionFeedbackRequest("el nino iba", true, null);

        var first = correctionService.registerFeedback(student.getId(), session.getId(), request);
        var retry = correctionService.registerFeedback(student.getId(), session.getId(), request);

        assertThat(retry.acceptedCorrection()).isTrue();
        assertThat(retry.selectedSuggestion()).isEqualTo(first.selectedSuggestion());
        // El reintento no reescribe nada: un solo diff, un solo reenvio a la IA.
        verify(wordCorrectionRepository, times(1)).deleteByCorrectionSessionId(session.getId());
        verify(wordCorrectionRepository, times(1)).saveAll(any());
        verify(aiCorrectionClient, times(1)).sendFeedback(student.getId(), "el nino iva", "el nino iba", true);
    }

    private Student readyStudent(String username) {
        return new Student(username, "UPC", "encoded-password");
    }

    private AiCorrectionResponse aiResponse(String correctedText, String modelVersion) {
        return new AiCorrectionResponse(student.getId(), correctedText, 50, List.of(correctedText), modelVersion);
    }

    private CorrectionSession completedSession(Student student, String suggestionsJson) {
        var session = new CorrectionSession(student, "texto original");
        session.complete("opcion ofrecida", 1, suggestionsJson, 100L);
        return session;
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
