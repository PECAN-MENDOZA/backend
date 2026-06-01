package com.mvp.backend.correction.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import com.mvp.backend.shared.dto.PagedResponse;
import com.mvp.backend.shared.exception.AiServiceException;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.repository.StudentRepository;

@Service
public class CorrectionService {

    private static final int MAX_SUGGESTIONS = 3;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final StudentRepository studentRepository;
    private final CorrectionSessionRepository sessionRepository;
    private final WordCorrectionRepository wordCorrectionRepository;
    private final AiCorrectionClient aiCorrectionClient;
    private final ObjectMapper objectMapper;

    public CorrectionService(
            StudentRepository studentRepository,
            CorrectionSessionRepository sessionRepository,
            WordCorrectionRepository wordCorrectionRepository,
            AiCorrectionClient aiCorrectionClient,
            ObjectMapper objectMapper) {
        this.studentRepository = studentRepository;
        this.sessionRepository = sessionRepository;
        this.wordCorrectionRepository = wordCorrectionRepository;
        this.aiCorrectionClient = aiCorrectionClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CorrectionSessionResponse process(UUID studentId, ProcessCorrectionRequest request) {
        var student = studentRepository.findById(studentId)
                .orElseThrow(() -> new NotFoundException("Student not found"));
        var session = sessionRepository.save(new CorrectionSession(student, request.originalText()));
        var aiResponse = aiCorrectionClient.correct(request.originalText(), request.additionalContext());
        if (aiResponse == null) {
            throw new AiServiceException("AI correction service returned an empty response", null);
        }
        List<String> suggestions = normalizeSuggestions(aiResponse.correctedText(), aiResponse.suggestions());
        session.complete(
                aiResponse.correctedText(),
                aiResponse.correctionsCount(),
                writeSuggestions(suggestions),
                aiResponse.confidence(),
                aiResponse.processingTimeMs());
        List<WordCorrection> wordCorrections = aiResponse.correctedWords() == null
                ? List.of()
                : aiResponse.correctedWords().stream()
                        .map(word -> new WordCorrection(
                                session,
                                word.originalWord(),
                                word.correctedWord(),
                                word.errorType(),
                                word.confidence(),
                                word.startPosition(),
                                word.endPosition()))
                        .toList();
        wordCorrectionRepository.saveAll(wordCorrections);
        return toResponse(session, wordCorrections);
    }

    @Transactional
    public CorrectionSessionResponse registerFeedback(UUID studentId, UUID sessionId, CorrectionFeedbackRequest request) {
        var session = findOwnedSession(studentId, sessionId);
        String selectedSuggestion = validateSelectedSuggestion(session, request);
        session.registerFeedback(selectedSuggestion, request.acceptedCorrection());
        return toResponse(session, wordCorrectionRepository.findByCorrectionSessionIdOrderByStartPosition(sessionId));
    }

    @Transactional(readOnly = true)
    public List<WordCorrectionResponse> getWords(UUID studentId, UUID sessionId) {
        findOwnedSession(studentId, sessionId);
        return wordCorrectionRepository.findByCorrectionSessionIdOrderByStartPosition(sessionId).stream()
                .map(WordCorrectionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<CorrectionSessionResponse> getSessions(UUID studentId, Pageable pageable) {
        Pageable limited = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 50), pageable.getSort());
        return PagedResponse.from(sessionRepository.findByStudentIdOrderByCreatedAtDesc(studentId, limited)
                .map(session -> toResponse(session, List.of())));
    }

    private CorrectionSession findOwnedSession(UUID studentId, UUID sessionId) {
        return sessionRepository.findByIdAndStudentId(sessionId, studentId)
                .orElseThrow(() -> new NotFoundException("Correction session not found"));
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
        if (request.acceptedCorrection() && selectedSuggestion == null) {
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
        var uniqueSuggestions = new LinkedHashMap<String, String>();
        addSuggestion(uniqueSuggestions, correctedText);
        if (suggestions != null) {
            suggestions.forEach(suggestion -> addSuggestion(uniqueSuggestions, suggestion));
        }
        return uniqueSuggestions.values().stream()
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private void addSuggestion(LinkedHashMap<String, String> suggestions, String suggestion) {
        String candidate = emptyToNull(suggestion);
        if (candidate != null) {
            suggestions.putIfAbsent(candidate.strip(), candidate);
        }
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
