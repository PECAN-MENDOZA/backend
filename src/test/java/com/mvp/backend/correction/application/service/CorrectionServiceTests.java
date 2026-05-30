package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.model.ErrorType;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.correction.infrastructure.ai.AiWordCorrectionResponse;
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
        assertThat(response.correctedWords()).hasSize(1);
        assertThat(response.correctedWords().getFirst().errorType()).isEqualTo(ErrorType.SPELLING);
        verify(wordCorrectionRepository).saveAll(any());
    }
}
