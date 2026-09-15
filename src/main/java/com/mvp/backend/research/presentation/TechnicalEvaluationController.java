package com.mvp.backend.research.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.research.application.dto.TechnicalEvaluationRequest;
import com.mvp.backend.research.application.dto.TechnicalEvaluationResponse;
import com.mvp.backend.research.application.service.TechnicalEvaluationService;

/**
 * Evaluacion tecnica independiente (F0.5, Precision, Recall). Endpoint y DTO propios, nunca mezclados
 * con los resultados de un estudio. Solo investigadores; cada uno ve las evaluaciones que registro.
 */
@RestController
@RequestMapping("/api/v1/research/technical-evaluations")
@PreAuthorize("hasRole('RESEARCHER')")
public class TechnicalEvaluationController {

    private final TechnicalEvaluationService service;

    public TechnicalEvaluationController(TechnicalEvaluationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TechnicalEvaluationResponse record(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TechnicalEvaluationRequest request) {
        return service.record(researcherId(jwt), request);
    }

    @GetMapping
    public List<TechnicalEvaluationResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String modelVersion) {
        return service.list(researcherId(jwt), modelVersion);
    }

    @GetMapping("/latest")
    public TechnicalEvaluationResponse latest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String modelVersion) {
        return service.latest(researcherId(jwt), modelVersion);
    }

    private static UUID researcherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
