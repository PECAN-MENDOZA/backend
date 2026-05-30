package com.mvp.backend.correction.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.correction.application.dto.CorrectionFeedbackRequest;
import com.mvp.backend.correction.application.dto.CorrectionSessionResponse;
import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.application.dto.WordCorrectionResponse;
import com.mvp.backend.correction.application.service.CorrectionService;
import com.mvp.backend.shared.dto.PagedResponse;

@RestController
@RequestMapping("/api/v1/corrections")
@PreAuthorize("hasRole('STUDENT')")
public class CorrectionController {

    private final CorrectionService correctionService;

    public CorrectionController(CorrectionService correctionService) {
        this.correctionService = correctionService;
    }

    @PostMapping("/process")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionSessionResponse process(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProcessCorrectionRequest request) {
        return correctionService.process(UUID.fromString(jwt.getSubject()), request);
    }

    @PatchMapping("/sessions/{sessionId}/feedback")
    public CorrectionSessionResponse registerFeedback(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId,
            @Valid @RequestBody CorrectionFeedbackRequest request) {
        return correctionService.registerFeedback(UUID.fromString(jwt.getSubject()), sessionId, request);
    }

    @GetMapping("/sessions/{sessionId}/words")
    public List<WordCorrectionResponse> getWords(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return correctionService.getWords(UUID.fromString(jwt.getSubject()), sessionId);
    }

    @GetMapping("/sessions")
    public PagedResponse<CorrectionSessionResponse> getSessions(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return correctionService.getSessions(UUID.fromString(jwt.getSubject()), pageable);
    }
}
