package com.mvp.backend.research.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

@ExtendWith(MockitoExtension.class)
class TechnicalEvaluationServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final String DATASET = "0123456789abcdef".repeat(4);

    @Mock
    private TechnicalEvaluationRepository repository;
    @Mock
    private ResearcherRepository researcherRepository;
    @Mock
    private ResearchAuditEventRepository auditRepository;

    private TechnicalEvaluationService service;
    private Researcher researcher;

    @BeforeEach
    void setUp() {
        service = new TechnicalEvaluationService(repository, researcherRepository, auditRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        researcher = new Researcher("lab@example.edu", "hash");
    }

    @Test
    void fZeroFiveWeightsPrecisionOverRecall() {
        // P = 0.8, R = 0.5 -> 1.25 * 0.4 / (0.2 + 0.5) = 0.714286 ; F1 = 0.615385
        assertThat(TechnicalEvaluation.fZeroFive(0.8, 0.5)).isCloseTo(0.7142857142857143, within(1e-12));
        assertThat(TechnicalEvaluation.fOne(0.8, 0.5)).isCloseTo(0.6153846153846154, within(1e-12));
        assertThat(TechnicalEvaluation.fZeroFive(0.0, 0.0)).isZero();
        assertThat(TechnicalEvaluation.fZeroFive(1.0, 1.0)).isEqualTo(1.0);
    }

    @Test
    void recordsAConsistentEvaluationAndAuditsIt() {
        when(researcherRepository.findById(researcher.getId())).thenReturn(Optional.of(researcher));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TechnicalEvaluationResponse response = service.record(researcher.getId(), new TechnicalEvaluationRequest(
                "t5-lora-global-v3", DATASET, "exact_token_edits_v1", 0.8, 0.5, 0.7142857, 40, 10, 40));

        assertThat(response.id()).isNotNull();
        assertThat(response.modelVersion()).isEqualTo("t5-lora-global-v3");
        assertThat(response.datasetSha256()).isEqualTo(DATASET);
        assertThat(response.scorerVersion()).isEqualTo("exact_token_edits_v1");
        assertThat(response.precision()).isEqualTo(0.8);
        assertThat(response.recall()).isEqualTo(0.5);
        assertThat(response.fZeroFive()).isEqualTo(0.7142857);
        assertThat(response.fOne()).isCloseTo(0.6153846, within(1e-6));
        assertThat(response.truePositives()).isEqualTo(40);
        assertThat(response.falsePositives()).isEqualTo(10);
        assertThat(response.falseNegatives()).isEqualTo(40);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.toString()).doesNotContain("lab@example.edu");

        ArgumentCaptor<ResearchAuditEvent> event = ArgumentCaptor.forClass(ResearchAuditEvent.class);
        verify(auditRepository).save(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("TECHNICAL_EVALUATION_RECORDED");
        assertThat(event.getValue().getStudy()).isNull();
        assertThat(event.getValue().getTargetId()).isEqualTo(response.id());
        assertThat(String.valueOf(event.getValue().getDetail())).contains("t5-lora-global-v3", DATASET, "exact_token_edits_v1");
    }

    @Test
    void rejectsAnFZeroFiveThatDoesNotMatchPrecisionAndRecall() {
        assertThatThrownBy(() -> service.record(researcher.getId(), new TechnicalEvaluationRequest(
                "t5-lora-global-v3", DATASET, "exact_token_edits_v1", 0.8, 0.5, 0.71, 40, 10, 40)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("F0.5");
        verify(repository, never()).save(any());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void entityRejectsOutOfRangeValues() {
        assertThatThrownBy(() -> new TechnicalEvaluation("m", DATASET, "exact_token_edits_v1", 1.2, 0.5, 0.9, 1, 1, 1, researcher, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TechnicalEvaluation("m", DATASET, "exact_token_edits_v1", 0.5, 0.5, 0.5, -1, 1, 1, researcher, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TechnicalEvaluation("m", "ABC", "exact_token_edits_v1", 0.5, 0.5, 0.5, 1, 1, 1, researcher, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TechnicalEvaluation("m", DATASET, "other_scorer", 0.5, 0.5, 0.5, 1, 1, 1, researcher, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TechnicalEvaluation("m", DATASET, "exact_token_edits_v1", 0.5, 0.5, 0.6, 1, 1, 1, researcher, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsAndFindsTheLatestEvaluationOfTheResearcher() {
        TechnicalEvaluation older = new TechnicalEvaluation("baseline", DATASET, "exact_token_edits_v1",
                0.5, 0.5, 0.5, 5, 5, 5, researcher, NOW.minusSeconds(60));
        TechnicalEvaluation newer = new TechnicalEvaluation("t5-lora-global-v3", DATASET, "exact_token_edits_v1",
                0.8, 0.5, 0.7142857142857143, 40, 10, 40, researcher, NOW);
        when(repository.findByCreatedByIdOrderByCreatedAtDesc(researcher.getId())).thenReturn(List.of(newer, older));
        when(repository.findFirstByCreatedByIdOrderByCreatedAtDesc(researcher.getId())).thenReturn(Optional.of(newer));
        when(repository.findFirstByCreatedByIdAndModelVersionOrderByCreatedAtDesc(researcher.getId(), "baseline"))
                .thenReturn(Optional.of(older));
        when(repository.findFirstByCreatedByIdAndModelVersionOrderByCreatedAtDesc(researcher.getId(), "missing"))
                .thenReturn(Optional.empty());

        assertThat(service.list(researcher.getId(), null)).extracting(TechnicalEvaluationResponse::modelVersion)
                .containsExactly("t5-lora-global-v3", "baseline");
        assertThat(service.latest(researcher.getId(), null).modelVersion()).isEqualTo("t5-lora-global-v3");
        assertThat(service.latest(researcher.getId(), "baseline").modelVersion()).isEqualTo("baseline");
        assertThatThrownBy(() -> service.latest(researcher.getId(), "missing"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.latest(UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
    }
}
