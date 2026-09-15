package com.mvp.backend.experiment.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.experiment.domain.model.ExperimentRun;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
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
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class StudentExperimentAuthorizationTests {

    private static final String BASE = "/api/v1/experiments";
    private static final List<String> FORBIDDEN_FIELDS =
            List.of("student", "name", "email", "username", "hash", "finaltext", "participantid");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResearcherRepository researcherRepository;

    @Autowired
    private ResearchStudyRepository studyRepository;

    @Autowired
    private StudyProtocolRepository protocolRepository;

    @Autowired
    private StudyParticipantRepository participantRepository;

    @Autowired
    private ExperimentRunRepository runRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    private UUID studentId;
    private UUID otherStudentId;
    private final UUID teacherId = UUID.randomUUID();
    private final UUID researcherId = UUID.randomUUID();
    private ResearchStudy study;
    private StudyProtocol protocol;

    @BeforeEach
    void setUp() {
        studentId = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash")).getId();
        otherStudentId = studentRepository.save(new Student("alumno-" + UUID.randomUUID(), "Colegio", "hash")).getId();
        Researcher researcher = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash"));
        study = new ResearchStudy("EXP-" + UUID.randomUUID().toString().substring(0, 8), "Flujo alumno", researcher);
        study.activate();
        study = studyRepository.save(study);
        protocol = new StudyProtocol(study, 1);
        protocol.addTask(TaskVariant.TASK_A, "Cuenta tu fin de semana");
        protocol.addTask(TaskVariant.TASK_B, "Describe tu escuela");
        protocol.activate();
        protocol = protocolRepository.save(protocol);
    }

    @Test
    void teacherCannotUseExperimentEndpoints() throws Exception {
        mockMvc.perform(get(BASE + "/runs/active")
                .with(jwt().jwt(token -> token.subject(teacherId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/access-code/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"ABCD2345\"}")
                .with(jwt().jwt(token -> token.subject(teacherId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void researcherCannotUseExperimentEndpoints() throws Exception {
        mockMvc.perform(get(BASE + "/runs/active")
                .with(jwt().jwt(token -> token.subject(researcherId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/runs/" + UUID.randomUUID() + "/start")
                .with(jwt().jwt(token -> token.subject(researcherId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE + "/runs/active")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/access-code/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"ABCD2345\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void codeFormatIsValidatedBeforeReachingTheService() throws Exception {
        mockMvc.perform(asStudent(studentId, post(BASE + "/access-code/redeem"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"abcd-234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.code").exists());
        mockMvc.perform(asStudent(studentId, patch(BASE + "/runs/" + UUID.randomUUID() + "/complete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto_final\":\"\",\"duracion_ms\":0,\"completion_key\":null,\"app_version\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.finalText").exists())
                .andExpect(jsonPath("$.validationErrors.durationMs").exists())
                .andExpect(jsonPath("$.validationErrors.completionKey").exists())
                .andExpect(jsonPath("$.validationErrors.appVersion").exists());
    }

    @Test
    void nothingToRestoreIsNotFound() throws Exception {
        mockMvc.perform(asStudent(studentId, get(BASE + "/runs/active"))).andExpect(status().isNotFound());
    }

    @Test
    void fullStudentLifecycleStaysPseudonymousAndIdempotent() throws Exception {
        String code = "ABCD2345";
        ExperimentRun pending = issueRun(1, code);

        // An unknown code and an unowned run answer generically.
        mockMvc.perform(asStudent(studentId, redeem("ZZZZ9999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Access code is invalid or unavailable"));

        JsonNode redeemed = json(mockMvc.perform(asStudent(studentId, redeem(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(pending.getId().toString()))
                .andExpect(jsonPath("$.participantCode").value("P-001"))
                .andExpect(jsonPath("$.condition").value("ASSISTED"))
                .andExpect(jsonPath("$.taskVariant").value("TASK_A"))
                .andExpect(jsonPath("$.promptText").value("Cuenta tu fin de semana"))
                .andExpect(jsonPath("$.status").value("PENDING")));
        assertNoIdentity(redeemed);
        String runId = redeemed.get("id").asText();

        // Another student cannot take over the code; the same student can redeem again.
        mockMvc.perform(asStudent(otherStudentId, redeem(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Access code is invalid or unavailable"));
        mockMvc.perform(asStudent(studentId, redeem(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(runId));

        // The confirmation screen can be restored before starting.
        mockMvc.perform(asStudent(studentId, get(BASE + "/runs/active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(asStudent(otherStudentId, get(BASE + "/runs/active"))).andExpect(status().isNotFound());
        mockMvc.perform(asStudent(otherStudentId, post(BASE + "/runs/" + runId + "/start")))
                .andExpect(status().isNotFound());

        mockMvc.perform(asStudent(studentId, post(BASE + "/runs/" + runId + "/start")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startedAt").exists());
        mockMvc.perform(asStudent(studentId, post(BASE + "/runs/" + runId + "/start")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        ExperimentRun started = runRepository.findById(pending.getId()).orElseThrow();
        assertThat(started.getAccessCodeHash()).isNull();
        assertThat(started.getBackendVersion()).isNotBlank();
        // Once started the code is consumed: it can no longer be redeemed.
        mockMvc.perform(asStudent(studentId, redeem(code))).andExpect(status().isBadRequest());
        mockMvc.perform(asStudent(studentId, get(BASE + "/runs/active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        UUID key = UUID.randomUUID();
        String body = completion("Texto final del alumno", 45_000L, key, "1.0.0");
        mockMvc.perform(asStudent(otherStudentId, complete(runId, body))).andExpect(status().isNotFound());
        JsonNode completed = json(mockMvc.perform(asStudent(studentId, complete(runId, body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")));
        assertNoIdentity(completed);
        assertThat(completed.has("finalText")).isFalse();
        // Same key: idempotent; the stored text and duration do not change.
        mockMvc.perform(asStudent(studentId, complete(runId, completion("Otro texto", 1L, key, "9.9.9"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        ExperimentRun done = runRepository.findById(pending.getId()).orElseThrow();
        assertThat(done.getFinalText()).isEqualTo("Texto final del alumno");
        assertThat(done.getDurationMs()).isEqualTo(45_000L);
        assertThat(done.getAppVersion()).isEqualTo("1.0.0");
        assertThat(done.getIncidentCount()).isZero();
        // Different key on a completed run: not active any more.
        mockMvc.perform(asStudent(studentId, complete(runId, completion("Otro", 1L, UUID.randomUUID(), "1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Run is not active"));
        mockMvc.perform(asStudent(studentId, get(BASE + "/runs/active"))).andExpect(status().isNotFound());

        // The same completion key on ANOTHER run is a conflict (uk_runs_completion_key).
        ExperimentRun second = issueRun(2, "EFGH6789");
        mockMvc.perform(asStudent(otherStudentId, redeem("EFGH6789"))).andExpect(status().isCreated());
        mockMvc.perform(asStudent(otherStudentId, post(BASE + "/runs/" + second.getId() + "/start")))
                .andExpect(status().isOk());
        mockMvc.perform(asStudent(otherStudentId, complete(second.getId().toString(),
                        completion("Texto de P-002", 30_000L, key, "1.0.0"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Completion key already used by another run"));
        assertThat(runRepository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(ExperimentRunStatus.ACTIVE);

        // The student cancels with a fixed reason; a completed run cannot be cancelled.
        mockMvc.perform(asStudent(otherStudentId, cancel(second.getId().toString(), "TECHNICAL_PROBLEM")))
                .andExpect(status().isNoContent());
        ExperimentRun cancelled = runRepository.findById(second.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ExperimentRunStatus.CANCELLED);
        assertThat(cancelled.getFailureReason()).isEqualTo("Cancelled by the student: technical problem");
        mockMvc.perform(asStudent(studentId, cancel(runId, "ABANDONED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only pending or active runs can be cancelled"));
        mockMvc.perform(asStudent(studentId, cancel(runId, "NOT_A_REASON"))).andExpect(status().isBadRequest());
    }

    @Test
    void expiredCodeIsMarkedExpiredEvenThoughRedemptionFails() throws Exception {
        String code = "JKLM2345";
        ExperimentRun run = transactionTemplate.execute(status -> {
            StudyParticipant participant = participantRepository.save(new StudyParticipant(study, 1));
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            return runRepository.save(new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), ExperimentCondition.ASSISTED,
                    AccessCode.hash(code), clock.instant().minusSeconds(1), clock.instant().minus(Duration.ofHours(1))));
        });

        mockMvc.perform(asStudent(studentId, redeem(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Access code is invalid or unavailable"));

        ExperimentRun expired = runRepository.findById(run.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ExperimentRunStatus.EXPIRED);
        assertThat(expired.getAccessCodeHash()).isNull();
    }

    @Test
    void failedRedemptionsAreRateLimitedPerStudent() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(asStudent(studentId, redeem("ZZZZ9999")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Access code is invalid or unavailable"));
        }
        mockMvc.perform(asStudent(studentId, redeem("ZZZZ9999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Too many failed redemption attempts, try again later"));
        mockMvc.perform(asStudent(otherStudentId, redeem("ZZZZ9999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Access code is invalid or unavailable"));
    }

    // ---------------------------------------------------------------- helpers

    private ExperimentRun issueRun(int participantNumber, String code) {
        return transactionTemplate.execute(status -> {
            StudyParticipant participant = participantRepository.save(new StudyParticipant(study, participantNumber));
            StudyProtocol loaded = protocolRepository.findById(protocol.getId()).orElseThrow();
            return runRepository.save(new ExperimentRun(participant, loaded,
                    loaded.findTask(TaskVariant.TASK_A).orElseThrow(), participant.nextCondition(0),
                    AccessCode.hash(code), clock.instant().plus(Duration.ofMinutes(30)), clock.instant()));
        });
    }

    private static MockHttpServletRequestBuilder asStudent(UUID id, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT")));
    }

    private static MockHttpServletRequestBuilder redeem(String code) {
        return post(BASE + "/access-code/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}");
    }

    private static MockHttpServletRequestBuilder complete(String runId, String body) {
        return patch(BASE + "/runs/" + runId + "/complete").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder cancel(String runId, String reason) {
        return post(BASE + "/runs/" + runId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
    }

    private static String completion(String text, long durationMs, UUID key, String appVersion) {
        return "{\"texto_final\":\"" + text + "\",\"duracion_ms\":" + durationMs
                + ",\"completion_key\":\"" + key + "\",\"app_version\":\"" + appVersion + "\"}";
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static void assertNoIdentity(JsonNode node) {
        node.propertyNames().forEach(field -> assertThat(field.toLowerCase())
                .as("field %s", field)
                .doesNotContain(FORBIDDEN_FIELDS.toArray(String[]::new)));
    }
}
