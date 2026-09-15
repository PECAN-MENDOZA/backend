package com.mvp.backend.research.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.experiment.domain.model.ExperimentRunStatus;
import com.mvp.backend.experiment.domain.repository.ExperimentRunRepository;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;
import com.mvp.backend.research.domain.repository.ResearcherRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ResearchAuthorizationTests {

    private static final List<String> IDENTITY_FIELDS = List.of("student", "name", "email", "username");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResearcherRepository researcherRepository;

    @Autowired
    private ExperimentRunRepository runRepository;

    @Autowired
    private ResearchAuditEventRepository auditRepository;

    private UUID researcherId;
    private UUID otherResearcherId;
    private final UUID teacherId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        researcherId = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash")).getId();
        otherResearcherId = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash")).getId();
    }

    @Test
    void teacherCannotListResearchStudies() throws Exception {
        mockMvc.perform(get("/api/v1/research/studies")
                .with(jwt().jwt(token -> token.subject(teacherId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotUseResearchEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/research/studies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"EXP-S\",\"title\":\"Intento\"}")
                .with(jwt().jwt(token -> token.subject(studentId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/research/studies/" + UUID.randomUUID() + "/runs")
                .with(jwt().jwt(token -> token.subject(studentId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/research/studies")).andExpect(status().isUnauthorized());
    }

    @Test
    void researcherCanListResearchStudies() throws Exception {
        mockMvc.perform(get("/api/v1/research/studies")
                .with(jwt().jwt(token -> token.subject(researcherId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER"))))
                .andExpect(status().isOk());
    }

    @Test
    void unknownAndUnownedStudiesAreIndistinguishable() throws Exception {
        JsonNode study = json(mockMvc.perform(asResearcher(researcherId, post("/api/v1/research/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code() + "\",\"title\":\"Estudio ajeno\"}"))
                .andExpect(status().isCreated()));

        String unknown = mockMvc.perform(asResearcher(otherResearcherId,
                        get("/api/v1/research/studies/" + UUID.randomUUID() + "/runs")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unowned = mockMvc.perform(asResearcher(otherResearcherId,
                        get("/api/v1/research/studies/" + study.get("id").asText() + "/runs")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(unknown).get("message").asText())
                .isEqualTo(objectMapper.readTree(unowned).get("message").asText())
                .isEqualTo("Study not found");
        mockMvc.perform(asResearcher(otherResearcherId,
                        post("/api/v1/research/studies/" + study.get("id").asText() + "/participants")))
                .andExpect(status().isNotFound());
    }

    @Test
    void reasonsAreValidatedBeforeReachingTheDomain() throws Exception {
        JsonNode study = json(mockMvc.perform(asResearcher(researcherId, post("/api/v1/research/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code() + "\",\"title\":\"Validacion\"}"))
                .andExpect(status().isCreated()));

        mockMvc.perform(asResearcher(researcherId, post("/api/v1/research/studies/" + study.get("id").asText()
                        + "/runs/" + UUID.randomUUID() + "/exclude"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"corto\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.reason").exists());
        mockMvc.perform(asResearcher(researcherId, post("/api/v1/research/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"title\":\"Sin codigo\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateStudyCodeIsAConflict() throws Exception {
        String studyCode = code();
        mockMvc.perform(asResearcher(researcherId, post("/api/v1/research/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + studyCode + "\",\"title\":\"Primero\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(asResearcher(otherResearcherId, post("/api/v1/research/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + studyCode + "\",\"title\":\"Repetido\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void fullAdministrationFlowStaysPseudonymous() throws Exception {
        String studyId = json(mockMvc.perform(asResearcher(researcherId, post("/api/v1/research/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code() + "\",\"title\":\"Flujo completo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")))
                .get("id").asText();
        String base = "/api/v1/research/studies/" + studyId;

        // Participants need an active study, which requires an active protocol.
        mockMvc.perform(asResearcher(researcherId, post(base + "/participants")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Study is not active"));

        JsonNode protocol = json(mockMvc.perform(asResearcher(researcherId, post(base + "/protocols"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskAPrompt\":\"Cuenta tu fin de semana\",\"taskBPrompt\":\"Describe tu escuela\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT")));

        mockMvc.perform(asResearcher(researcherId,
                        post(base + "/protocols/" + protocol.get("id").asText() + "/activate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(asResearcher(researcherId, get("/api/v1/research/studies")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + studyId + "')].status").value("ACTIVE"))
                .andExpect(jsonPath("$[?(@.id == '" + studyId + "')].activeProtocolVersion").value(1));

        JsonNode first = json(mockMvc.perform(asResearcher(researcherId, post(base + "/participants")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pseudonym").value("P-001"))
                .andExpect(jsonPath("$.sequence").value("ASSISTED_FIRST"))
                .andExpect(jsonPath("$.nextSession.task").value("TASK_A"))
                .andExpect(jsonPath("$.nextSession.condition").value("ASSISTED")));
        JsonNode second = json(mockMvc.perform(asResearcher(researcherId, post(base + "/participants")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pseudonym").value("P-002"))
                .andExpect(jsonPath("$.sequence").value("UNASSISTED_FIRST"))
                .andExpect(jsonPath("$.nextSession.condition").value("UNASSISTED")));
        assertNoIdentity(first);
        assertNoIdentity(second);

        JsonNode access = json(mockMvc.perform(asResearcher(researcherId,
                        post(base + "/participants/" + first.get("id").asText() + "/access-code")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pseudonym").value("P-001"))
                .andExpect(jsonPath("$.task").value("TASK_A"))
                .andExpect(jsonPath("$.condition").value("ASSISTED")));
        assertNoIdentity(access);
        String plaintext = access.get("code").asText();
        assertThat(plaintext).matches(AccessCode.PATTERN);
        String runId = access.get("runId").asText();

        // Only the SHA-256 hex is stored; the plaintext never reaches the database or the audit log.
        var run = runRepository.findById(UUID.fromString(runId)).orElseThrow();
        assertThat(run.getAccessCodeHash()).isEqualTo(AccessCode.hash(plaintext)).matches("[0-9a-f]{64}");
        assertThat(runRepository.findByAccessCodeHashAndStatus(AccessCode.hash(plaintext), ExperimentRunStatus.PENDING))
                .isPresent();
        assertThat(auditRepository.findByStudyIdOrderByCreatedAtDesc(UUID.fromString(studyId)))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(String.valueOf(event.getDetail())).doesNotContain(plaintext));

        // A second code for the same participant is rejected while the first is open.
        mockMvc.perform(asResearcher(researcherId,
                        post(base + "/participants/" + first.get("id").asText() + "/access-code")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Participant already has an open run"));
        mockMvc.perform(asResearcher(researcherId, get(base + "/participants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasOpenRun").value(true))
                .andExpect(jsonPath("$[1].hasOpenRun").value(false));

        // Exclusion is not allowed on a pending run; revocation is, and it keeps the row.
        mockMvc.perform(asResearcher(researcherId, post(base + "/runs/" + runId + "/exclude"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Motivo con longitud suficiente\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asResearcher(researcherId, post(base + "/access-codes/" + runId + "/revoke")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(asResearcher(researcherId, post(base + "/access-codes/" + runId + "/revoke")))
                .andExpect(status().isBadRequest());

        JsonNode runs = json(mockMvc.perform(asResearcher(researcherId, get(base + "/runs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(runId))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].pseudonym").value("P-001")));
        assertNoIdentity(runs.get(0));
        assertThat(runs.get(0).has("finalText")).isFalse();

        // After revocation the participant can receive a fresh code, still for the first session.
        mockMvc.perform(asResearcher(researcherId,
                        post(base + "/participants/" + first.get("id").asText() + "/access-code")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.task").value("TASK_A"))
                .andExpect(jsonPath("$.condition").value("ASSISTED"));

        // The other researcher can neither see nor act on this study.
        mockMvc.perform(asResearcher(otherResearcherId, get(base + "/runs"))).andExpect(status().isNotFound());
        mockMvc.perform(asResearcher(otherResearcherId, post(base + "/access-codes/" + runId + "/revoke")))
                .andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder asResearcher(UUID id, MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER")));
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String code() {
        return "EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static void assertNoIdentity(JsonNode node) {
        node.propertyNames().forEach(field -> assertThat(field.toLowerCase())
                .as("field %s", field)
                .doesNotContain(IDENTITY_FIELDS.toArray(String[]::new)));
    }
}
