package com.mvp.backend.correction.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.mvp.backend.correction.application.dto.CorrectionFeedbackRequest;
import com.mvp.backend.correction.application.dto.ProcessCorrectionRequest;
import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.domain.model.ResearchStudy;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.model.StudyParticipant;
import com.mvp.backend.research.domain.model.StudyProtocol;
import com.mvp.backend.research.domain.model.TaskVariant;
import com.mvp.backend.research.domain.repository.ResearchStudyRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.research.domain.repository.StudyParticipantRepository;
import com.mvp.backend.research.domain.repository.StudyProtocolRepository;
import com.mvp.backend.shared.exception.AiServiceException;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

/**
 * Real transaction manager over H2: proves that the AI incident is committed in its own
 * transaction while the failed correction rolls back, and that assisted sessions are linked
 * to their run with the model version recorded once.
 */
@SpringBootTest
class CorrectionExperimentIntegrationTests {

    @Autowired
    private CorrectionService correctionService;

    @Autowired
    private CorrectionSessionRepository sessionRepository;

    @Autowired
    private ExperimentRunRepository runRepository;

    @Autowired
    private ResearcherRepository researcherRepository;

    @Autowired
    private ResearchStudyRepository studyRepository;

    @Autowired
    private StudyProtocolRepository protocolRepository;

    @Autowired
    private StudyParticipantRepository participantRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @MockitoBean
    private AiCorrectionClient aiCorrectionClient;

    private Student student;
    private ResearchStudy study;
    private StudyProtocol protocol;

    @BeforeEach
    void setUp() {
        student = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash"));
        Researcher researcher = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash"));
        study = new ResearchStudy("EXP-" + UUID.randomUUID().toString().substring(0, 8), "Correccion", researcher);
        study.activate();
        study = studyRepository.save(study);
        protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Cuenta tu fin de semana");
        protocol.addTask(TaskVariant.TASK_B, "Describe tu escuela");
        protocol.activate();
        protocol = protocolRepository.save(protocol);
    }

    @Test
    void aiFailureLeavesAnIncidentOnTheRunEvenThoughTheCorrectionRollsBack() {
        UUID runId = activeRun(1, ExperimentCondition.ASSISTED);
        when(aiCorrectionClient.correct(anyString(), any())).thenThrow(new AiServiceException("unavailable", null));

        assertThatThrownBy(() -> correctionService.process(
                student.getId(), new ProcessCorrectionRequest("texto del alumno", runId)))
                .isInstanceOf(AiServiceException.class);

        ExperimentRun stored = runRepository.findById(runId).orElseThrow();
        assertThat(stored.getIncidentCount()).isEqualTo(1);
        assertThat(stored.getFailureReason()).isEqualTo("AI_REQUEST_FAILED").doesNotContain("texto del alumno");
        assertThat(stored.isActive()).isTrue();
        assertThat(sessionRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(), PageRequest.of(0, 10)))
                .as("the failed correction session is rolled back")
                .isEmpty();
    }

    @Test
    void assistedCorrectionsAreLinkedToTheRunAndKeepTheFirstModelVersion() {
        UUID runId = activeRun(2, ExperimentCondition.ASSISTED);
        when(aiCorrectionClient.correct("primer texto", student.getId()))
                .thenReturn(new AiCorrectionResponse(student.getId(), "primer texto", 10, List.of(), "beto-lora-1.2"));
        when(aiCorrectionClient.correct("segundo texto", student.getId()))
                .thenReturn(new AiCorrectionResponse(student.getId(), "segundo texto", 10, List.of(), "beto-lora-1.3"));

        var first = correctionService.process(student.getId(), new ProcessCorrectionRequest("primer texto", runId));
        var second = correctionService.process(student.getId(), new ProcessCorrectionRequest("segundo texto", runId));

        transactionTemplate.executeWithoutResult(status -> {
            CorrectionSession firstSession = sessionRepository.findById(first.sessionId()).orElseThrow();
            CorrectionSession secondSession = sessionRepository.findById(second.sessionId()).orElseThrow();
            assertThat(firstSession.getExperimentRun().getId()).isEqualTo(runId);
            assertThat(secondSession.getExperimentRun().getId()).isEqualTo(runId);
        });
        ExperimentRun stored = runRepository.findById(runId).orElseThrow();
        assertThat(stored.getModelVersion()).isEqualTo("beto-lora-1.2");
        assertThat(stored.getIncidentCount()).isEqualTo(1);
        assertThat(stored.getFailureReason()).isEqualTo("MODEL_VERSION_CHANGED");

        // Feedback of an experimental session is stored (reason included) but never forwarded to the AI.
        var feedback = correctionService.registerFeedback(student.getId(), first.sessionId(),
                new CorrectionFeedbackRequest("primer texto", false, null, "UNDO"));
        assertThat(feedback.acceptedCorrection()).isFalse();
        assertThat(sessionRepository.findById(first.sessionId()).orElseThrow().getFeedbackReason()).isEqualTo("UNDO");
        verify(aiCorrectionClient, never()).sendFeedback(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void unassistedRunNeverReachesTheAiNorPersistsASession() {
        UUID runId = activeRun(3, ExperimentCondition.UNASSISTED);

        assertThatThrownBy(() -> correctionService.process(
                student.getId(), new ProcessCorrectionRequest("texto", runId)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Contextual correction is disabled for this experiment run");

        verifyNoInteractions(aiCorrectionClient);
        assertThat(sessionRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(), PageRequest.of(0, 10))).isEmpty();
        assertThat(runRepository.findById(runId).orElseThrow().getIncidentCount()).isZero();
    }

    /** ACTIVE run of {@code student} under the given condition, committed before the test body runs. */
    private UUID activeRun(int participantNumber, ExperimentCondition condition) {
        return transactionTemplate.execute(status -> {
            StudyParticipant participant = new StudyParticipant(study, participantNumber);
            participant.linkStudent(student);
            participant = participantRepository.save(participant);
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            ExperimentRun run = new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), condition,
                    AccessCode.hash("ABCD2345"), clock.instant().plus(Duration.ofMinutes(30)), clock.instant());
            run.redeem(clock.instant());
            run.start(clock.instant());
            return runRepository.save(run).getId();
        });
    }
}
