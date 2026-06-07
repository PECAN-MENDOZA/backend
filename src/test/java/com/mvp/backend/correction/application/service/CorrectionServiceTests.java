package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.application.dto.CorrectionFeedbackRequest;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
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

    private CorrectionService correctionService;

    @BeforeEach
    void setUp() {
        correctionService = new CorrectionService(
                studentRepository,
                sessionRepository,
                wordCorrectionRepository,
                aiCorrectionClient,
                new ObjectMapper());
    }

    @Test
    void persistsAiCorrectionSuggestions() {
        var student = readyStudent("student_01");
        var aiResponse = new AiCorrectionResponse(
                student.getId(),
                "los ninos fueron al patio",
                267,
                List.of("los ninos fueron al patio"));
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
                List.of("texto alternativo", "texto principal", "texto alternativo", "tercera opcion", "cuarta opcion"));
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
        when(sessionRepository.findByIdAndStudentId(session.getId(), student.getId())).thenReturn(Optional.of(session));
        when(wordCorrectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("el nino iba", true));

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
    void ignoringFeedbackStoresNoWordCorrections() {
        var student = readyStudent("student_07");
        var session = new CorrectionSession(student, "el nino iva");
        session.complete("el nino iba", 0, "[\"el nino iba\"]", 100L);
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentId(session.getId(), student.getId())).thenReturn(Optional.of(session));

        var response = correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest(null, false));

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
        when(sessionRepository.findByIdAndStudentId(session.getId(), student.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest("opcion inventada", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Selected suggestion was not offered for this correction session");
    }

    @Test
    void requiresSelectedSuggestionWhenFeedbackAcceptsCorrection() {
        var student = readyStudent("student_05");
        var session = completedSession(student, "[\"opcion ofrecida\"]");
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.findByIdAndStudentId(session.getId(), student.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest(null, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Accepted correction requires a selected suggestion");
    }

    private Student readyStudent(String username) {
        return new Student(username, "UPC", "encoded-password");
    }

    private CorrectionSession completedSession(Student student, String suggestionsJson) {
        var session = new CorrectionSession(student, "texto original");
        session.complete("opcion ofrecida", 1, suggestionsJson, 100L);
        return session;
    }
}
