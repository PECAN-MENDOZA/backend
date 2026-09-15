package com.mvp.backend.research.application.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.research.application.dto.TechnicalEvaluationRequest;
import com.mvp.backend.research.application.dto.TechnicalEvaluationResponse;
import com.mvp.backend.research.domain.model.ResearchAuditEvent;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.TechnicalEvaluation;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.TechnicalEvaluationRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.NotFoundException;

/**
 * Evaluaciones tecnicas versionadas (F0.5 sobre el conjunto reservado). Separadas de los resultados con
 * participantes: no dependen de ningun estudio y cada investigador ve solo las que registro.
 */
@Service
public class TechnicalEvaluationService {

    private final TechnicalEvaluationRepository repository;
    private final ResearcherRepository researcherRepository;
    private final ResearchAuditEventRepository auditRepository;
    private final Clock clock;

    public TechnicalEvaluationService(
            TechnicalEvaluationRepository repository,
            ResearcherRepository researcherRepository,
            ResearchAuditEventRepository auditRepository,
            Clock clock) {
        this.repository = repository;
        this.researcherRepository = researcherRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    /** Recalcula F0.5 a partir de P y R y rechaza el registro si difiere del valor enviado en mas de 1e-6. */
    @Transactional
    public TechnicalEvaluationResponse record(UUID researcherId, TechnicalEvaluationRequest request) {
        double expected = TechnicalEvaluation.fZeroFive(request.precision(), request.recall());
        if (Math.abs(expected - request.fZeroFive()) > TechnicalEvaluation.F_TOLERANCE) {
            throw new BusinessException(String.format(java.util.Locale.ROOT,
                    "F0.5 does not match precision and recall (expected %.6f)", expected));
        }
        Researcher researcher = researcherRepository.findById(researcherId)
                .orElseThrow(() -> new NotFoundException("Researcher not found"));
        TechnicalEvaluation evaluation;
        try {
            evaluation = new TechnicalEvaluation(request.modelVersion(), request.datasetSha256(), request.scorerVersion(),
                    request.precision(), request.recall(), request.fZeroFive(), request.truePositives(),
                    request.falsePositives(), request.falseNegatives(), researcher, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        evaluation = repository.save(evaluation);
        auditRepository.save(new ResearchAuditEvent(researcher, null, "TECHNICAL_EVALUATION_RECORDED", evaluation.getId(),
                "modelVersion=" + evaluation.getModelVersion() + ", datasetSha256=" + evaluation.getDatasetSha256()
                        + ", scorerVersion=" + evaluation.getScorerVersion() + ", f0.5=" + evaluation.getFZeroFive()));
        return TechnicalEvaluationResponse.from(evaluation);
    }

    @Transactional(readOnly = true)
    public List<TechnicalEvaluationResponse> list(UUID researcherId, String modelVersion) {
        List<TechnicalEvaluation> evaluations = modelVersion == null || modelVersion.isBlank()
                ? repository.findByCreatedByIdOrderByCreatedAtDesc(researcherId)
                : repository.findByCreatedByIdAndModelVersionOrderByCreatedAtDesc(researcherId, modelVersion.strip());
        return evaluations.stream().map(TechnicalEvaluationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TechnicalEvaluationResponse latest(UUID researcherId, String modelVersion) {
        return (modelVersion == null || modelVersion.isBlank()
                ? repository.findFirstByCreatedByIdOrderByCreatedAtDesc(researcherId)
                : repository.findFirstByCreatedByIdAndModelVersionOrderByCreatedAtDesc(researcherId, modelVersion.strip()))
                .map(TechnicalEvaluationResponse::from)
                .orElseThrow(() -> new NotFoundException("Technical evaluation not found"));
    }
}
