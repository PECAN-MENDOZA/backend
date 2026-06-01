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

import com.mvp.backend.correction.application.dto.CorrectionFeedbackRequest;
import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.model.ErrorType;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.correction.infrastructure.ai.AiWordCorrectionResponse;
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
    void persistsAiCorrectionAndWordDetails() {
        var student = new Student("student_01", "UPC", "encoded-password");
        var aiWord = new AiWordCorrectionResponse("ninos", "ninos", ErrorType.SPELLING, 0.98, 4, 9);
        var aiResponse = new AiCorrectionResponse(
                "los ninos fueron al patio",
                1,
                List.of("los ninos fueron al patio"),
                0.93,
                267,
                List.of(aiWord));
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.save(any(CorrectionSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiCorrectionClient.correct("los ninos fueron al patio", "")).thenReturn(aiResponse);

        var response = correctionService.process(
                student.getId(),
                new ProcessCorrectionRequest("los ninos fueron al patio", ""));

        assertThat(response.correctionsCount()).isEqualTo(1);
        assertThat(response.suggestionOptions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.text()).isEqualTo("los ninos fueron al patio");
            assertThat(suggestion.confidence()).isEqualTo(0.93);
            assertThat(suggestion.recommended()).isTrue();
        });
        assertThat(response.correctedWords()).hasSize(1);
        assertThat(response.correctedWords().getFirst().errorType()).isEqualTo(ErrorType.SPELLING);
        verify(wordCorrectionRepository).saveAll(any());
    }

    @Test
    void normalizesSuggestionOrderRemovesDuplicatesAndLimitsAlternatives() {
        var student = student("student_03");
        var aiResponse = new AiCorrectionResponse(
                "texto principal",
                1,
                List.of("texto alternativo", "texto principal", "texto alternativo", "tercera opcion", "cuarta opcion"),
                0.91,
                120,
                List.of());
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(sessionRepository.save(any(CorrectionSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(aiCorrectionClient.correct("texto original", "")).thenReturn(aiResponse);

        var response = correctionService.process(student.getId(), new ProcessCorrectionRequest("texto original", ""));

        assertThat(response.suggestions()).containsExactly("texto principal", "texto alternativo", "tercera opcion");
        assertThat(response.suggestionOptions()).extracting(option -> option.recommended())
                .containsExactly(true, false, false);
        assertThat(response.suggestionOptions()).extracting(option -> option.confidence())
                .containsExactly(0.91, null, null);
    }

    @Test
    void rejectsAcceptedFeedbackWhenSuggestionWasNotOffered() {
        var student = student("student_04");
        var session = completedSession(student, "[\"opcion ofrecida\"]");
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
        var student = student("student_05");
        var session = completedSession(student, "[\"opcion ofrecida\"]");
        when(sessionRepository.findByIdAndStudentId(session.getId(), student.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> correctionService.registerFeedback(
                student.getId(),
                session.getId(),
                new CorrectionFeedbackRequest(null, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Accepted correction requires a selected suggestion");
    }

    private Student student(String username) {
        return new Student(username, "UPC", "encoded-password");
    }

    private CorrectionSession completedSession(Student student, String suggestionsJson) {
        var session = new CorrectionSession(student, "texto original");
        session.complete("opcion ofrecida", 1, suggestionsJson, 0.89, 100L);
        return session;
    }
}
