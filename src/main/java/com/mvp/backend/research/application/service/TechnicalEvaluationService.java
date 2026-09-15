package com.mvp.backend.research.application.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.research.application.dto.CategoryResult;
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
 * participantes: no dependen de ningun estudio y cada investigador ve solo las que registro. El desglose
 * opcional por categoria (spec §11.4) se valida con las mismas reglas que el vector global y se guarda
 * como JSON junto a la evaluacion.
 */
@Service
public class TechnicalEvaluationService {

    private static final TypeReference<List<CategoryResult>> CATEGORY_LIST = new TypeReference<>() {
    };

    private final TechnicalEvaluationRepository repository;
    private final ResearcherRepository researcherRepository;
    private final ResearchAuditEventRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TechnicalEvaluationService(
            TechnicalEvaluationRepository repository,
            ResearcherRepository researcherRepository,
            ResearchAuditEventRepository auditRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.researcherRepository = researcherRepository;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Recalcula P y R desde TP/FP/FN (con denominador 0 el valor enviado debe ser 0) y F0.5 desde P y R; rechaza
     * el registro si alguno difiere del valor enviado en mas de 1e-6. La entidad repite ambas comprobaciones.
     * Cada categoria del desglose se somete a las mismas reglas y no puede repetirse.
     */
    @Transactional
    public TechnicalEvaluationResponse record(UUID researcherId, TechnicalEvaluationRequest request) {
        requireConsistent("", request.precision(), request.recall(), request.fZeroFive(), request.truePositives(),
                request.falsePositives(), request.falseNegatives());
        List<CategoryResult> categories = normalizeCategories(request.categories());
        Researcher researcher = researcherRepository.findById(researcherId)
                .orElseThrow(() -> new NotFoundException("Researcher not found"));
        TechnicalEvaluation evaluation;
        try {
            evaluation = new TechnicalEvaluation(request.modelVersion(), request.datasetSha256(), request.scorerVersion(),
                    request.precision(), request.recall(), request.fZeroFive(), request.truePositives(),
                    request.falsePositives(), request.falseNegatives(), writeCategories(categories), researcher,
                    clock.instant());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        evaluation = repository.save(evaluation);
        auditRepository.save(new ResearchAuditEvent(researcher, null, "TECHNICAL_EVALUATION_RECORDED", evaluation.getId(),
                "modelVersion=" + evaluation.getModelVersion() + ", datasetSha256=" + evaluation.getDatasetSha256()
                        + ", scorerVersion=" + evaluation.getScorerVersion() + ", f0.5=" + evaluation.getFZeroFive()
                        + ", categories=" + categories.size()));
        return TechnicalEvaluationResponse.from(evaluation, categories);
    }

    @Transactional(readOnly = true)
    public List<TechnicalEvaluationResponse> list(UUID researcherId, String modelVersion) {
        List<TechnicalEvaluation> evaluations = modelVersion == null || modelVersion.isBlank()
                ? repository.findByCreatedByIdOrderByCreatedAtDesc(researcherId)
                : repository.findByCreatedByIdAndModelVersionOrderByCreatedAtDesc(researcherId, modelVersion.strip());
        return evaluations.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TechnicalEvaluationResponse latest(UUID researcherId, String modelVersion) {
        return (modelVersion == null || modelVersion.isBlank()
                ? repository.findFirstByCreatedByIdOrderByCreatedAtDesc(researcherId)
                : repository.findFirstByCreatedByIdAndModelVersionOrderByCreatedAtDesc(researcherId, modelVersion.strip()))
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Technical evaluation not found"));
    }

    // ------------------------------------------------------------------ helpers

    private static void requireConsistent(String prefix, double precision, double recall, double fZeroFive,
            int tp, int fp, int fn) {
        if (!TechnicalEvaluation.countsConsistent(precision, recall, tp, fp, fn)) {
            throw new BusinessException(prefix + TechnicalEvaluation.COUNTS_MISMATCH);
        }
        double expected = TechnicalEvaluation.fZeroFive(precision, recall);
        if (Math.abs(expected - fZeroFive) > TechnicalEvaluation.F_TOLERANCE) {
            throw new BusinessException(String.format(Locale.ROOT,
                    "%sF0.5 does not match precision and recall (expected %.6f)", prefix, expected));
        }
    }

    /** Valida cada categoria como el vector global (nombre recortado, sin repetidos); ausente equivale a vacio. */
    private static List<CategoryResult> normalizeCategories(List<CategoryResult> categories) {
        if (categories == null || categories.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        return categories.stream().map(category -> {
            String name = category.category().strip();
            if (!seen.add(name)) {
                throw new BusinessException("Category '" + name + "' is repeated");
            }
            requireConsistent("Category '" + name + "': ", category.precision(), category.recall(), category.f05(),
                    category.tp(), category.fp(), category.fn());
            return new CategoryResult(name, category.tp(), category.fp(), category.fn(), category.precision(),
                    category.recall(), category.f05());
        }).toList();
    }

    private String writeCategories(List<CategoryResult> categories) {
        if (categories.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(categories);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize evaluation categories", e);
        }
    }

    private List<CategoryResult> readCategories(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CATEGORY_LIST);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not deserialize evaluation categories", e);
        }
    }

    private TechnicalEvaluationResponse toResponse(TechnicalEvaluation evaluation) {
        return TechnicalEvaluationResponse.from(evaluation, readCategories(evaluation.getCategoriesJson()));
    }
}
