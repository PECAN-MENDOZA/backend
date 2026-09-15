package com.mvp.backend.research.presentation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.mvp.backend.correction.infrastructure.ai.AiCorrectionClient;
import com.mvp.backend.correction.infrastructure.ai.AiCorrectionResponse;
import com.mvp.backend.experiment.domain.model.AccessCode;
import com.mvp.backend.research.domain.repository.ResearchAuditEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recorrido completo del flujo de investigacion sobre la cadena real (login → estudio → protocolo →
 * participantes → codigos → sesiones del alumno → anotacion ciega → resultados → evaluacion tecnica),
 * con un investigador, un docente y dos alumnos. Verifica en cada paso los codigos de estado, los
 * campos clave y, al final, que ninguna respuesta de investigacion contenga identidad de alumnos,
 * datos del docente ni codigos en claro (salvo la respuesta que los emite).
 *
 * <p>Valores esperados calculados a mano a partir de los textos de la fixture (ver el reporte de la
 * tarea 8): PEO 25/40 (P-001) y 20/50 (P-002); PPM 2/5 y 2/2; TAS agregado 1/3; TAS aceptada 1/1.
 */
@SpringBootTest(properties = {
        "app.researcher.email=" + ResearchApiIntegrationTests.RESEARCHER_EMAIL,
        "app.researcher.password=" + ResearchApiIntegrationTests.RESEARCHER_PASSWORD})
@AutoConfigureMockMvc
class ResearchApiIntegrationTests {

    static final String RESEARCHER_EMAIL = "investigadora-e2e@tesis.local";
    /** Valor exclusivo de esta prueba; el despliegue real lo recibe por RESEARCHER_PASSWORD. */
    static final String RESEARCHER_PASSWORD = "solo-para-pruebas-e2e-2026";

    private static final String MODEL_VERSION = "beto-lora-global-v1";
    private static final String APP_VERSION = "keyboard-e2e-1.0";
    private static final String RESEARCH = "/api/v1/research";
    private static final String EXPERIMENTS = "/api/v1/experiments";
    private static final String CORRECTIONS = "/api/v1/corrections";
    private static final String DATASET_SHA256 = "0123456789abcdef".repeat(4);

    // Fixture: final texts, durations and orthography errors per participant x condition.
    private static final String P1_ASSISTED_TEXT = "hola mundo que tal";            // 4 words, 1 error
    private static final String P1_UNASSISTED_TEXT = "ola mundo ke tal amigo";      // 5 words, 2 errors
    private static final String P2_UNASSISTED_TEXT = "uno dos tres cuatro";         // 4 words, 2 errors
    private static final String P2_ASSISTED_TEXT = "uno dos tres cuatro cinco";     // 5 words, 1 error
    private static final Map<String, Integer> ORTHOGRAPHY_SCORES = Map.of(
            P1_ASSISTED_TEXT, 1, P1_UNASSISTED_TEXT, 2, P2_UNASSISTED_TEXT, 2, P2_ASSISTED_TEXT, 1);
    /** Semantic scores keyed by the original text of the suggestion (0 harmful, 1 minor, 2 safe). */
    private static final Map<String, Integer> SEMANTIC_SCORES = Map.of("ola mundo", 0, "ke tal", 2, "sinco amigos", 1);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ResearchAuditEventRepository auditRepository;
    @MockitoBean
    private AiCorrectionClient aiClient;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String teacherEmail = "docente-" + suffix + "@colegio.test";
    private final String teacherUsername = "docente-" + suffix;
    private final String teacherInstitution = "Colegio E2E " + suffix;

    private String researcherToken;
    private String teacherToken;
    private UUID teacherId;
    private StudentAccount student1;
    private StudentAccount student2;

    private UUID studyId;
    private UUID protocolId;
    private UUID participant1;
    private UUID participant2;
    private final Map<String, UUID> runs = new LinkedHashMap<>();          // "P-001/ASSISTED" -> run id
    private final Map<String, UUID> sessions = new LinkedHashMap<>();      // original text -> session id
    private final List<String> plaintextCodes = new ArrayList<>();
    private final List<ResearchBody> researchBodies = new ArrayList<>();
    private UUID orthographyBatch;
    private UUID semanticBatch;
    private String orthographySha256;
    private String semanticSha256;

    private record StudentAccount(UUID id, String username, String pin, String realName, String notes, String token) {
    }

    private record ResearchBody(String label, String body, boolean carriesPlaintextCode) {
    }

    @BeforeEach
    void stubAi() {
        doAnswer(invocation -> {
            String original = invocation.getArgument(0);
            UUID studentId = invocation.getArgument(1);
            return switch (original) {
                case "ola mundo" -> new AiCorrectionResponse(studentId, "hola mundo", 12L, List.of("hola mundo!"), MODEL_VERSION);
                case "ke tal" -> new AiCorrectionResponse(studentId, "que tal", 12L, List.of(), MODEL_VERSION);
                case "sinco amigos" -> new AiCorrectionResponse(studentId, "cinco amigos", 12L, List.of("cinco amigos"), MODEL_VERSION);
                default -> new AiCorrectionResponse(studentId, original, 12L, List.of(), MODEL_VERSION);
            };
        }).when(aiClient).correct(anyString(), any());
    }

    @Test
    void wholeResearchFlowIsAuthorizedPseudonymousAndCompatible() throws Exception {
        loginsAndRoleBoundaries();
        studyProtocolAndParticipants();
        protocolVersionHistoryIsListedNewestFirst();
        // Sessions in the order a pilot would run them; the server fixes prompt, condition and order.
        session(participant1, student1, "P-001", "TASK_A", "ASSISTED", P1_ASSISTED_TEXT, 120_000);
        session(participant2, student2, "P-002", "TASK_A", "UNASSISTED", P2_UNASSISTED_TEXT, 120_000);
        session(participant1, student1, "P-001", "TASK_B", "UNASSISTED", P1_UNASSISTED_TEXT, 60_000);
        session(participant2, student2, "P-002", "TASK_B", "ASSISTED", P2_ASSISTED_TEXT, 150_000);
        protocolIsCompleteForBothParticipants();
        runsAreListedWithoutTextsOrIdentity();
        orthographyAnnotation();
        semanticAnnotation();
        results();
        technicalEvaluations();
        technicalFailureAndStudyClosure();
        privacySweep();
    }

    // ------------------------------------------------------------------ step 1

    private void loginsAndRoleBoundaries() throws Exception {
        // Researcher bootstrapped from app.researcher.* (env RESEARCHER_EMAIL / RESEARCHER_PASSWORD).
        JsonNode researcher = json(mockMvc.perform(post("/api/v1/auth/staff/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("email", RESEARCHER_EMAIL, "password", RESEARCHER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("RESEARCHER")));
        researcherToken = researcher.get("token").asText();
        mockMvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(obj("email", RESEARCHER_EMAIL, "password", "otra-clave")))
                .andExpect(status().isUnauthorized());

        // Teacher registration and both login routes keep working (compatibility).
        JsonNode teacher = json(mockMvc.perform(post("/api/v1/auth/teachers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("username", teacherUsername, "email", teacherEmail, "phone", "999000111",
                                "institution", teacherInstitution, "password", "DocentePass123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("TEACHER")));
        teacherId = UUID.fromString(teacher.get("userId").asText());
        teacherToken = json(mockMvc.perform(post("/api/v1/auth/teachers/login").contentType(MediaType.APPLICATION_JSON)
                        .content(obj("email", teacherEmail, "password", "DocentePass123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TEACHER"))).get("token").asText();
        mockMvc.perform(post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(obj("email", teacherEmail, "password", "DocentePass123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TEACHER"));

        // Two students created by the teacher (real name and notes stay on the teacher side only).
        student1 = createStudent("Nombre Real Alumno Uno " + suffix, "Nota confidencial uno " + suffix);
        student2 = createStudent("Nombre Real Alumno Dos " + suffix, "Nota confidencial dos " + suffix);
        assertThat(student1.username()).isNotEqualTo(student2.username());

        // Role boundaries over the real filter chain and method security.
        mockMvc.perform(get(RESEARCH + "/studies")).andExpect(status().isUnauthorized());
        for (String token : List.of(teacherToken, student1.token())) {
            mockMvc.perform(bearer(token, get(RESEARCH + "/studies"))).andExpect(status().isForbidden());
            mockMvc.perform(bearer(token, post(RESEARCH + "/studies")).contentType(MediaType.APPLICATION_JSON)
                            .content(obj("code", "X", "title", "X"))).andExpect(status().isForbidden());
            mockMvc.perform(bearer(token, get(RESEARCH + "/technical-evaluations"))).andExpect(status().isForbidden());
            mockMvc.perform(bearer(token, get(RESEARCH + "/studies/" + UUID.randomUUID() + "/results")))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(bearer(researcherToken, get(EXPERIMENTS + "/runs/active"))).andExpect(status().isForbidden());
        mockMvc.perform(bearer(researcherToken, post(EXPERIMENTS + "/access-code/redeem"))
                        .contentType(MediaType.APPLICATION_JSON).content(obj("code", "ABCDEFGH")))
                .andExpect(status().isForbidden());
        mockMvc.perform(bearer(researcherToken, post(CORRECTIONS + "/process"))
                        .contentType(MediaType.APPLICATION_JSON).content(obj("texto_original", "ola")))
                .andExpect(status().isForbidden());
        mockMvc.perform(bearer(teacherToken, get(EXPERIMENTS + "/runs/active"))).andExpect(status().isForbidden());
        // Teacher routes remain the teacher's.
        mockMvc.perform(bearer(teacherToken, get("/api/v1/teachers/students"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(bearer(researcherToken, get("/api/v1/teachers/students"))).andExpect(status().isForbidden());
    }

    private StudentAccount createStudent(String realName, String notes) throws Exception {
        JsonNode created = json(mockMvc.perform(bearer(teacherToken, post("/api/v1/teachers/students/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("studentRealName", realName, "notes", notes)))
                .andExpect(status().isCreated()));
        String username = created.get("username").asText();
        String pin = created.get("pin").asText();
        UUID id = UUID.fromString(created.get("studentId").asText());
        String token = json(mockMvc.perform(post("/api/v1/auth/students/login").contentType(MediaType.APPLICATION_JSON)
                        .content(obj("username", username, "password", pin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.userId").value(id.toString()))).get("token").asText();
        return new StudentAccount(id, username, pin, realName, notes, token);
    }

    // ------------------------------------------------------------------ step 2

    private void studyProtocolAndParticipants() throws Exception {
        JsonNode study = research("create study", researcher(post(RESEARCH + "/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(obj("code", "EXP-" + suffix, "title", "Piloto teclado adaptativo")), 201);
        studyId = UUID.fromString(study.get("id").asText());
        assertThat(study.get("status").asText()).isEqualTo("DRAFT");

        // Participants need an active study, and the study becomes active with its first active protocol.
        mockMvc.perform(researcher(post(studyUrl("/participants")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Study is not active"));

        JsonNode protocol = research("create protocol", researcher(post(studyUrl("/protocols")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(obj("taskAPrompt", "Cuenta que hiciste el fin de semana",
                        "taskBPrompt", "Describe tu lugar favorito")), 201);
        protocolId = UUID.fromString(protocol.get("id").asText());
        assertThat(protocol.get("version").asInt()).isEqualTo(1);
        assertThat(protocol.get("status").asText()).isEqualTo("DRAFT");
        JsonNode activated = research("activate protocol",
                researcher(post(studyUrl("/protocols/" + protocolId + "/activate"))), 200);
        assertThat(activated.get("status").asText()).isEqualTo("ACTIVE");
        JsonNode studies = research("list studies", researcher(get(RESEARCH + "/studies")), 200);
        assertThat(studies).hasSize(1);
        assertThat(studies.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(studies.get(0).get("activeProtocolVersion").asInt()).isEqualTo(1);

        JsonNode p1 = research("create participant 1", researcher(post(studyUrl("/participants"))), 201);
        JsonNode p2 = research("create participant 2", researcher(post(studyUrl("/participants"))), 201);
        participant1 = UUID.fromString(p1.get("id").asText());
        participant2 = UUID.fromString(p2.get("id").asText());
        assertThat(p1.get("pseudonym").asText()).isEqualTo("P-001");
        assertThat(p1.get("sequence").asText()).isEqualTo("ASSISTED_FIRST");
        assertThat(p2.get("pseudonym").asText()).isEqualTo("P-002");
        assertThat(p2.get("sequence").asText()).isEqualTo("UNASSISTED_FIRST");

        JsonNode participants = research("list participants", researcher(get(studyUrl("/participants"))), 200);
        assertThat(participants).hasSize(2);
        assertThat(participants.get(0).get("nextSession").get("task").asText()).isEqualTo("TASK_A");
        assertThat(participants.get(0).get("nextSession").get("condition").asText()).isEqualTo("ASSISTED");
        assertThat(participants.get(1).get("nextSession").get("condition").asText()).isEqualTo("UNASSISTED");
        assertThat(participants.toString()).doesNotContain("studentId", "email", "notes", "institution", "name");
        assertThat(participants.get(0).propertyNames()).containsExactlyInAnyOrder(
                "id", "pseudonym", "sequence", "completedRuns", "protocolCompleted", "hasOpenRun", "nextSession", "createdAt");
    }

    // ----------------------------------------------------------------- step 2b

    /**
     * Segundo estudio, solo para ejercitar {@code GET …/protocols}: dos versiones y se activa la
     * segunda, de modo que la version mas reciente queda primero y la anterior en RETIRED, sin tocar
     * el protocolo (version 1) que el resto del flujo asume activo en {@link #studyId}.
     */
    private void protocolVersionHistoryIsListedNewestFirst() throws Exception {
        JsonNode study2 = research("create study 2", researcher(post(RESEARCH + "/studies"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(obj("code", "EXP2-" + suffix, "title", "Piloto de control")), 201);
        UUID study2Id = UUID.fromString(study2.get("id").asText());
        String study2Url = RESEARCH + "/studies/" + study2Id;

        JsonNode v1 = research("create protocol v1", researcher(post(study2Url + "/protocols"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(obj("taskAPrompt", "Consigna A v1", "taskBPrompt", "Consigna B v1")), 201);
        UUID v1Id = UUID.fromString(v1.get("id").asText());
        research("activate protocol v1", researcher(post(study2Url + "/protocols/" + v1Id + "/activate")), 200);

        JsonNode v2 = research("create protocol v2", researcher(post(study2Url + "/protocols"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(obj("taskAPrompt", "Consigna A v2", "taskBPrompt", "Consigna B v2")), 201);
        UUID v2Id = UUID.fromString(v2.get("id").asText());
        research("activate protocol v2", researcher(post(study2Url + "/protocols/" + v2Id + "/activate")), 200);

        JsonNode protocols = research("list protocol versions", researcher(get(study2Url + "/protocols")), 200);
        assertThat(protocols).hasSize(2);
        assertThat(protocols.get(0).get("id").asText()).isEqualTo(v2Id.toString());
        assertThat(protocols.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(protocols.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(protocols.get(0).get("taskAPrompt").asText()).isEqualTo("Consigna A v2");
        assertThat(protocols.get(0).get("taskBPrompt").asText()).isEqualTo("Consigna B v2");
        assertThat(protocols.get(1).get("id").asText()).isEqualTo(v1Id.toString());
        assertThat(protocols.get(1).get("version").asInt()).isEqualTo(1);
        assertThat(protocols.get(1).get("status").asText()).isEqualTo("RETIRED");

        // Un estudio ajeno o inexistente responde igual que las demas lecturas: 404, no una pista de enumeracion.
        mockMvc.perform(researcher(get(RESEARCH + "/studies/" + UUID.randomUUID() + "/protocols")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Study not found"));

        // Un token de docente no es investigador: la seguridad de metodo bloquea antes de mirar el dueño.
        mockMvc.perform(bearer(teacherToken, get(study2Url + "/protocols"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ step 3

    /**
     * One experimental session: the researcher issues a code, the student redeems, starts, writes (with the
     * IA only in ASSISTED), completes idempotently, and the run is closed to corrections and feedback.
     */
    private void session(UUID participantId, StudentAccount student, String pseudonym, String task, String condition,
                         String finalText, long durationMs) throws Exception {
        JsonNode issued = research("access-code " + pseudonym + " " + condition,
                researcher(post(studyUrl("/participants/" + participantId + "/access-code"))), 201, true);
        String code = issued.get("code").asText();
        assertThat(code).matches(AccessCode.PATTERN);
        assertThat(issued.get("pseudonym").asText()).isEqualTo(pseudonym);
        assertThat(issued.get("task").asText()).isEqualTo(task);
        assertThat(issued.get("condition").asText()).isEqualTo(condition);
        plaintextCodes.add(code);
        // A second code while one is open is refused.
        mockMvc.perform(researcher(post(studyUrl("/participants/" + participantId + "/access-code"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Participant already has an open run"));

        StudentAccount other = student == student1 ? student2 : student1;
        JsonNode redeemed = json(mockMvc.perform(bearer(student.token(), post(EXPERIMENTS + "/access-code/redeem"))
                        .contentType(MediaType.APPLICATION_JSON).content(obj("code", code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantCode").value(pseudonym))
                .andExpect(jsonPath("$.condition").value(condition))
                .andExpect(jsonPath("$.taskVariant").value(task))
                .andExpect(jsonPath("$.promptText").value(task.equals("TASK_A")
                        ? "Cuenta que hiciste el fin de semana" : "Describe tu lugar favorito"))
                .andExpect(jsonPath("$.status").value("PENDING")));
        UUID runId = UUID.fromString(redeemed.get("id").asText());
        runs.put(pseudonym + "/" + condition, runId);
        assertThat(redeemed.propertyNames()).containsExactlyInAnyOrder(
                "id", "participantCode", "condition", "taskVariant", "promptText", "status", "startedAt", "expiresAt");
        // The code is bound to the student who redeemed it (single-use across students): anyone else gets
        // the generic error, and the bound student may redeem it again until the run starts.
        mockMvc.perform(bearer(other.token(), post(EXPERIMENTS + "/access-code/redeem"))
                        .contentType(MediaType.APPLICATION_JSON).content(obj("code", code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Access code is invalid or unavailable"));
        mockMvc.perform(bearer(student.token(), post(EXPERIMENTS + "/access-code/redeem"))
                        .contentType(MediaType.APPLICATION_JSON).content(obj("code", code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(runId.toString()));

        // Redeemed-but-not-started is restorable; the other student has nothing to restore.
        mockMvc.perform(bearer(student.token(), get(EXPERIMENTS + "/runs/active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(bearer(other.token(), get(EXPERIMENTS + "/runs/active"))).andExpect(status().isNotFound());
        mockMvc.perform(bearer(other.token(), post(EXPERIMENTS + "/runs/" + runId + "/start")))
                .andExpect(status().isNotFound());
        mockMvc.perform(bearer(student.token(), post(EXPERIMENTS + "/runs/" + runId + "/start")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startedAt").exists());
        mockMvc.perform(bearer(student.token(), post(EXPERIMENTS + "/access-code/redeem"))
                        .contentType(MediaType.APPLICATION_JSON).content(obj("code", code)))
                .andExpect(status().isBadRequest());

        if (condition.equals("ASSISTED")) {
            assistedCorrections(student, runId, pseudonym);
        } else {
            unassistedCorrections(student, runId);
        }

        // Completion is idempotent by completion_key; the second call returns the very same body.
        UUID completionKey = UUID.randomUUID();
        String completion = obj("texto_final", finalText, "duracion_ms", durationMs,
                "completion_key", completionKey.toString(), "app_version", APP_VERSION);
        JsonNode completed = json(mockMvc.perform(bearer(student.token(), patch(EXPERIMENTS + "/runs/" + runId + "/complete"))
                        .contentType(MediaType.APPLICATION_JSON).content(completion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")));
        JsonNode repeated = json(mockMvc.perform(bearer(student.token(), patch(EXPERIMENTS + "/runs/" + runId + "/complete"))
                        .contentType(MediaType.APPLICATION_JSON).content(completion))
                .andExpect(status().isOk()));
        assertThat(repeated).isEqualTo(completed);
        mockMvc.perform(bearer(student.token(), patch(EXPERIMENTS + "/runs/" + runId + "/complete"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("texto_final", "otro texto", "duracion_ms", 1000, "completion_key",
                                UUID.randomUUID().toString(), "app_version", APP_VERSION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Run is not active"));
        mockMvc.perform(bearer(student.token(), get(EXPERIMENTS + "/runs/active"))).andExpect(status().isNotFound());

        // The completed run is terminal for the keyboard: no corrections, no feedback.
        clearInvocations(aiClient);
        mockMvc.perform(bearer(student.token(), post(CORRECTIONS + "/process"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("texto_original", "tarde", "id_ejecucion", runId.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not active")));
        verifyNoInteractions(aiClient);
        if (condition.equals("ASSISTED")) {
            UUID session = sessions.get(pseudonym.equals("P-001") ? "ola mundo" : "sinco amigos");
            mockMvc.perform(bearer(student.token(), patch(CORRECTIONS + "/sessions/" + session + "/feedback"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(obj("acepto_correccion", false, "motivo", "UNDO")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Feedback is closed for this experiment run"));
        }
    }

    private void assistedCorrections(StudentAccount student, UUID runId, String pseudonym) throws Exception {
        if (pseudonym.equals("P-001")) {
            // Suggestion accepted (evaluated = accepted "hola mundo").
            UUID first = correct(student, runId, "ola mundo", "hola mundo", List.of("hola mundo", "hola mundo!"));
            mockMvc.perform(bearer(student.token(), patch(CORRECTIONS + "/sessions/" + first + "/feedback"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(obj("sugerencia_elegida", "hola mundo", "acepto_correccion", true,
                                    "texto_final", "hola mundo")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.acepto_correccion").value(true))
                    .andExpect(jsonPath("$.sugerencia_elegida").value("hola mundo"));
            // Suggestion accepted and then undone (evaluated = first offered "que tal", not accepted).
            UUID second = correct(student, runId, "ke tal", "que tal", List.of("que tal"));
            mockMvc.perform(bearer(student.token(), patch(CORRECTIONS + "/sessions/" + second + "/feedback"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(obj("sugerencia_elegida", "que tal", "acepto_correccion", true)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.acepto_correccion").value(true));
            mockMvc.perform(bearer(student.token(), patch(CORRECTIONS + "/sessions/" + second + "/feedback"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(obj("acepto_correccion", false, "motivo", "UNDO")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.acepto_correccion").value(false))
                    .andExpect(jsonPath("$.sugerencia_elegida").doesNotExist());
            mockMvc.perform(bearer(student.token(), patch(CORRECTIONS + "/sessions/" + second + "/feedback"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(obj("sugerencia_elegida", "que tal", "acepto_correccion", true)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Feedback cannot re-accept a corrected text after undo"));
        } else {
            // No feedback at all: evaluated = first offered, not accepted.
            correct(student, runId, "sinco amigos", "cinco amigos", List.of("cinco amigos"));
        }
        // Experimental feedback is never forwarded to the IA (the LoRA is global).
        verify(aiClient, org.mockito.Mockito.never()).sendFeedback(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private UUID correct(StudentAccount student, UUID runId, String original, String corrected, List<String> offered)
            throws Exception {
        JsonNode session = json(mockMvc.perform(bearer(student.token(), post(CORRECTIONS + "/process"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("texto_original", original, "id_ejecucion", runId.toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.texto_original").value(original))
                .andExpect(jsonPath("$.texto_corregido").value(corrected)));
        assertThat(session.get("suggestions")).extracting(JsonNode::asText).containsExactlyElementsOf(offered);
        verify(aiClient).correct(original, student.id());
        UUID id = UUID.fromString(session.get("id_sesion").asText());
        sessions.put(original, id);
        return id;
    }

    private void unassistedCorrections(StudentAccount student, UUID runId) throws Exception {
        // The backend blocks the IA on its own for UNASSISTED, before contacting it.
        clearInvocations(aiClient);
        mockMvc.perform(bearer(student.token(), post(CORRECTIONS + "/process"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("texto_original", "sin ayuda", "id_ejecucion", runId.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Contextual correction is disabled for this experiment run"));
        verifyNoInteractions(aiClient);
        // A run of another student is indistinguishable from a missing one.
        StudentAccount other = student == student1 ? student2 : student1;
        mockMvc.perform(bearer(other.token(), post(CORRECTIONS + "/process"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("texto_original", "ajeno", "id_ejecucion", runId.toString())))
                .andExpect(status().isNotFound());
        verifyNoInteractions(aiClient);
        if (student == student2) {
            // Normal (non-experimental) use keeps working without id_ejecucion; it never joins the study.
            mockMvc.perform(bearer(student.token(), post(CORRECTIONS + "/process"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(obj("texto_original", "uso normal sin ejecucion")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.texto_corregido").value("uso normal sin ejecucion"));
            verify(aiClient).correct("uso normal sin ejecucion", student.id());
        }
    }

    private void protocolIsCompleteForBothParticipants() throws Exception {
        JsonNode participants = research("list participants after protocol", researcher(get(studyUrl("/participants"))), 200);
        for (JsonNode participant : participants) {
            assertThat(participant.get("completedRuns").asLong()).isEqualTo(2);
            assertThat(participant.get("protocolCompleted").asBoolean()).isTrue();
            assertThat(participant.get("hasOpenRun").asBoolean()).isFalse();
            assertThat(participant.get("nextSession").isNull()).isTrue();
        }
        mockMvc.perform(researcher(post(studyUrl("/participants/" + participant1 + "/access-code"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Participant already completed both conditions"));
    }

    // ------------------------------------------------------------------ step 4

    private void runsAreListedWithoutTextsOrIdentity() throws Exception {
        JsonNode listed = research("list runs", researcher(get(studyUrl("/runs"))), 200);
        assertThat(listed).hasSize(4);
        for (JsonNode run : listed) {
            assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(run.get("incidentCount").asInt()).isZero();
            assertThat(run.get("excluded").asBoolean()).isFalse();
            assertThat(run.get("appVersion").asText()).isEqualTo(APP_VERSION);
            assertThat(run.get("backendVersion").asText()).isEqualTo("local");
            String modelVersion = run.get("modelVersion").isNull() ? null : run.get("modelVersion").asText();
            assertThat(modelVersion).isEqualTo(run.get("condition").asText().equals("ASSISTED") ? MODEL_VERSION : null);
            assertThat(run.propertyNames()).doesNotContain("finalText", "text", "student", "studentId", "accessCodeHash");
        }
        assertThat(listed.toString()).doesNotContain(P1_ASSISTED_TEXT, P1_UNASSISTED_TEXT, P2_ASSISTED_TEXT, P2_UNASSISTED_TEXT);
    }

    private void orthographyAnnotation() throws Exception {
        JsonNode batch = research("create orthography batch",
                researcher(post(studyUrl("/annotation-batches")).param("kind", "ORTHOGRAPHY")), 201);
        orthographyBatch = UUID.fromString(batch.get("id").asText());
        orthographySha256 = batch.get("exportSha256").asText();
        assertThat(batch.get("rowCount").asInt()).isEqualTo(4);
        assertThat(batch.get("adjudicationCurrent").asBoolean()).isFalse();

        String csv = download(orthographyBatch, orthographySha256);
        List<String[]> rows = rows(csv, "sample_code,text,score");
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(row -> row[1])
                .containsExactlyInAnyOrder(P1_ASSISTED_TEXT, P1_UNASSISTED_TEXT, P2_ASSISTED_TEXT, P2_UNASSISTED_TEXT);
        assertThat(rows).allSatisfy(row -> assertThat(row[0]).matches("T-" + AccessCode.PATTERN));
        assertThat(csv).doesNotContain("ASSISTED", "P-00", "TASK_", student1.username(), student2.username(),
                MODEL_VERSION, APP_VERSION);

        // Rater 2 disagrees on one sample; the adjudication settles it.
        String rater1 = scored(rows, row -> ORTHOGRAPHY_SCORES.get(row[1]));
        String rater2 = scored(rows, row -> row[1].equals(P2_UNASSISTED_TEXT) ? 1 : ORTHOGRAPHY_SCORES.get(row[1]));
        research("import orthography RATER_1", upload(orthographyBatch, "RATER_1", "Ana", rater1), 201);
        mockMvc.perform(upload(orthographyBatch, "RATER_2", "ana", rater2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("RATER_1 and RATER_2 must be distinct raters"));
        mockMvc.perform(upload(orthographyBatch, "ADJUDICATED", "Consenso", rater1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Adjudication requires two complete rater imports"));
        JsonNode agreement = research("import orthography RATER_2", upload(orthographyBatch, "RATER_2", "Beto", rater2), 201);
        assertThat(agreement.get("agreement").get("complete").asBoolean()).isTrue();
        // rater1 [1,2,2,1] vs rater2 [1,2,1,1]: exact 3/4; linear kappa = 1 - 0.25/0.5.
        assertThat(agreement.get("agreement").get("exactAgreement").asDouble()).isCloseTo(0.75, within(1e-9));
        assertThat(agreement.get("agreement").get("weightedKappa").asDouble()).isCloseTo(0.5, within(1e-9));
        JsonNode adjudicated = research("import orthography ADJUDICATED",
                upload(orthographyBatch, "ADJUDICATED", "Consenso", rater1), 201);
        assertThat(adjudicated.get("adjudicationCurrent").asBoolean()).isTrue();
        assertThat(adjudicated.get("completedSlots")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("RATER_1", "RATER_2", "ADJUDICATED");
        research("list batches", researcher(get(studyUrl("/annotation-batches"))), 200);
    }

    private void semanticAnnotation() throws Exception {
        JsonNode batch = research("create semantic batch",
                researcher(post(studyUrl("/annotation-batches")).param("kind", "SEMANTIC")), 201);
        semanticBatch = UUID.fromString(batch.get("id").asText());
        semanticSha256 = batch.get("exportSha256").asText();
        // Only the three sessions linked to ASSISTED runs; the normal-use session never appears.
        assertThat(batch.get("rowCount").asInt()).isEqualTo(3);

        String csv = download(semanticBatch, semanticSha256);
        List<String[]> rows = rows(csv, "sample_code,original_text,suggestion,score");
        assertThat(rows).extracting(row -> row[1] + " -> " + row[2])
                .containsExactlyInAnyOrder("ola mundo -> hola mundo", "ke tal -> que tal", "sinco amigos -> cinco amigos");
        assertThat(csv).doesNotContain("uso normal", "sin ayuda", "ASSISTED", "P-00", "acept", "UNDO");

        String rater1 = scored(rows, row -> SEMANTIC_SCORES.get(row[1]));
        String rater2 = scored(rows, row -> row[1].equals("sinco amigos") ? 2 : SEMANTIC_SCORES.get(row[1]));
        research("import semantic RATER_1", upload(semanticBatch, "RATER_1", "Ana", rater1), 201);
        JsonNode agreement = research("import semantic RATER_2", upload(semanticBatch, "RATER_2", "Beto", rater2), 201);
        // rater1 [0,2,1] vs rater2 [0,2,2]: exact 2/3; linear kappa = 1 - (1/6)/(1/2).
        assertThat(agreement.get("agreement").get("exactAgreement").asDouble()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(agreement.get("agreement").get("weightedKappa").asDouble()).isCloseTo(2.0 / 3, within(1e-9));
        JsonNode adjudicated = research("import semantic ADJUDICATED",
                upload(semanticBatch, "ADJUDICATED", "Consenso", rater1), 201);
        assertThat(adjudicated.get("adjudicationCurrent").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ step 5

    private void results() throws Exception {
        ResultActions actions = mockMvc.perform(researcher(get(studyUrl("/results"))))
                .andExpect(status().isOk())
                // Sample: both participants have a complete pair; every completed run is included.
                .andExpect(jsonPath("$.sample.participantsTotal").value(2))
                .andExpect(jsonPath("$.sample.participantsIncluded").value(2))
                .andExpect(jsonPath("$.sample.participantsWithIncompletePair").value(0))
                .andExpect(jsonPath("$.sample.participantsWithoutEligibleRun").value(0))
                .andExpect(jsonPath("$.sample.runsCompleted").value(4))
                .andExpect(jsonPath("$.sample.runsIncluded").value(4))
                .andExpect(jsonPath("$.sample.runsExcluded").value(0))
                .andExpect(jsonPath("$.sample.runsInIncompletePairs").value(0))
                .andExpect(jsonPath("$.sample.runsWithoutCountableWords").value(0))
                .andExpect(jsonPath("$.orthographyAnnotation.status").value("ADJUDICATED"))
                .andExpect(jsonPath("$.orthographyAnnotation.batchId").value(orthographyBatch.toString()))
                .andExpect(jsonPath("$.orthographyAnnotation.exportSha256").value(orthographySha256))
                .andExpect(jsonPath("$.semanticAnnotation.status").value("ADJUDICATED"))
                .andExpect(jsonPath("$.semanticAnnotation.batchId").value(semanticBatch.toString()))
                .andExpect(jsonPath("$.semanticAnnotation.exportSha256").value(semanticSha256))
                // PEO: P-001 25 vs 40, P-002 20 vs 50 -> deltas [-15, -30].
                .andExpect(jsonPath("$.peo.paired.n").value(2))
                .andExpect(jsonPath("$.peo.participantsAnalyzed").value(2))
                .andExpect(jsonPath("$.peo.participantsWithoutCountableWords").value(0))
                .andExpect(jsonPath("$.peo.runsAnalyzed").value(4))
                .andExpect(jsonPath("$.peo.paired.assistedMean").value(near(22.5, 1e-9)))
                .andExpect(jsonPath("$.peo.paired.unassistedMean").value(near(45.0, 1e-9)))
                .andExpect(jsonPath("$.peo.paired.meanDelta").value(near(-22.5, 1e-9)))
                .andExpect(jsonPath("$.peo.paired.sdDelta").value(near(10.606602, 1e-6)))
                .andExpect(jsonPath("$.peo.paired.ci95Lower").value(near(-117.796536, 1e-5)))
                .andExpect(jsonPath("$.peo.paired.ci95Upper").value(near(72.796536, 1e-5)))
                .andExpect(jsonPath("$.peo.paired.tStatistic").value(near(-3.0, 1e-9)))
                .andExpect(jsonPath("$.peo.paired.pValue").value(near(0.204833, 1e-6)))
                .andExpect(jsonPath("$.peo.paired.cohenDz").value(near(-2.121320, 1e-6)))
                .andExpect(jsonPath("$.peo.relativeReductionMean").value(near(48.75, 1e-9)))
                .andExpect(jsonPath("$.peo.relativeReductionN").value(2))
                .andExpect(jsonPath("$.peo.relativeReductionSkipped").value(0))
                .andExpect(jsonPath("$.peo.upperCiBelowZero").value(false))
                // PPM: P-001 2 vs 5, P-002 2 vs 2 -> deltas [-3, 0]; descriptive without a margin.
                .andExpect(jsonPath("$.ppm.paired.n").value(2))
                .andExpect(jsonPath("$.ppm.participantsAnalyzed").value(2))
                .andExpect(jsonPath("$.ppm.runsAnalyzed").value(4))
                .andExpect(jsonPath("$.ppm.paired.assistedMean").value(near(2.0, 1e-9)))
                .andExpect(jsonPath("$.ppm.paired.unassistedMean").value(near(3.5, 1e-9)))
                .andExpect(jsonPath("$.ppm.paired.meanDelta").value(near(-1.5, 1e-9)))
                .andExpect(jsonPath("$.ppm.paired.sdDelta").value(near(2.121320, 1e-6)))
                .andExpect(jsonPath("$.ppm.paired.ci95Lower").value(near(-20.559307, 1e-5)))
                .andExpect(jsonPath("$.ppm.paired.ci95Upper").value(near(17.559307, 1e-5)))
                .andExpect(jsonPath("$.ppm.paired.tStatistic").value(near(-1.0, 1e-9)))
                .andExpect(jsonPath("$.ppm.paired.pValue").value(near(0.5, 1e-9)))
                .andExpect(jsonPath("$.ppm.descriptive").value(true))
                .andExpect(jsonPath("$.ppm.nonInferiorityMargin").doesNotExist())
                .andExpect(jsonPath("$.ppm.nonInferior").doesNotExist())
                // TAS: P-001 1 harmful of 2, P-002 0 of 1 -> pooled 1/3 (Wilson), participant mean 25.
                .andExpect(jsonPath("$.tas.participantsEvaluated").value(2))
                .andExpect(jsonPath("$.tas.participantsWithoutDenominator").value(0))
                .andExpect(jsonPath("$.tas.runsAnalyzed").value(2))
                .andExpect(jsonPath("$.tas.suggestionsEvaluated").value(3))
                .andExpect(jsonPath("$.tas.harmfulSuggestions").value(1))
                .andExpect(jsonPath("$.tas.pooledRate").value(near(33.333333, 1e-6)))
                .andExpect(jsonPath("$.tas.pooledCi95Lower").value(near(6.149194, 1e-5)))
                .andExpect(jsonPath("$.tas.pooledCi95Upper").value(near(79.234040, 1e-5)))
                .andExpect(jsonPath("$.tas.participantMean").value(near(25.0, 1e-9)))
                .andExpect(jsonPath("$.tas.descriptive").value(true))
                .andExpect(jsonPath("$.tas.limit").doesNotExist())
                .andExpect(jsonPath("$.tas.upperCiBelowLimit").doesNotExist())
                // TAS aceptada: only P-001 accepted a suggestion (the harmful one) -> 1/1; P-002 has no denominator.
                .andExpect(jsonPath("$.tasAccepted.participantsEvaluated").value(1))
                .andExpect(jsonPath("$.tasAccepted.participantsWithoutDenominator").value(1))
                .andExpect(jsonPath("$.tasAccepted.runsAnalyzed").value(1))
                .andExpect(jsonPath("$.tasAccepted.suggestionsEvaluated").value(1))
                .andExpect(jsonPath("$.tasAccepted.harmfulSuggestions").value(1))
                .andExpect(jsonPath("$.tasAccepted.pooledRate").value(near(100.0, 1e-9)))
                .andExpect(jsonPath("$.tasAccepted.pooledCi95Lower").value(near(20.654931, 1e-5)))
                .andExpect(jsonPath("$.tasAccepted.pooledCi95Upper").value(near(100.0, 1e-9)))
                .andExpect(jsonPath("$.tasAccepted.descriptive").value(true))
                // Per-participant rows.
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].pseudonym").value("P-001"))
                .andExpect(jsonPath("$.participants[0].peoAssisted").value(near(25.0, 1e-9)))
                .andExpect(jsonPath("$.participants[0].peoUnassisted").value(near(40.0, 1e-9)))
                .andExpect(jsonPath("$.participants[0].peoDelta").value(near(-15.0, 1e-9)))
                .andExpect(jsonPath("$.participants[0].peoRelativeReduction").value(near(37.5, 1e-9)))
                .andExpect(jsonPath("$.participants[0].ppmAssisted").value(near(2.0, 1e-9)))
                .andExpect(jsonPath("$.participants[0].ppmUnassisted").value(near(5.0, 1e-9)))
                .andExpect(jsonPath("$.participants[0].ppmDelta").value(near(-3.0, 1e-9)))
                .andExpect(jsonPath("$.participants[0].tas").value(near(50.0, 1e-9)))
                .andExpect(jsonPath("$.participants[0].tasAccepted").value(near(100.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].pseudonym").value("P-002"))
                .andExpect(jsonPath("$.participants[1].peoAssisted").value(near(20.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].peoUnassisted").value(near(50.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].peoDelta").value(near(-30.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].peoRelativeReduction").value(near(60.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].ppmAssisted").value(near(2.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].ppmUnassisted").value(near(2.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].ppmDelta").value(near(0.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].tas").value(near(0.0, 1e-9)))
                .andExpect(jsonPath("$.participants[1].tasAccepted").doesNotExist())
                // Provenance: versions observed on the included runs and the two adjudicated batches.
                .andExpect(jsonPath("$.provenance.protocolVersions").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.provenance.modelVersions").value(org.hamcrest.Matchers.contains(MODEL_VERSION)))
                .andExpect(jsonPath("$.provenance.backendVersions").value(org.hamcrest.Matchers.contains("local")))
                .andExpect(jsonPath("$.provenance.appVersions").value(org.hamcrest.Matchers.contains(APP_VERSION)))
                .andExpect(jsonPath("$.provenance.datasets.length()").value(2))
                .andExpect(jsonPath("$.provenance.datasets[?(@.kind=='ORTHOGRAPHY')].batchId").value(orthographyBatch.toString()))
                .andExpect(jsonPath("$.provenance.datasets[?(@.kind=='ORTHOGRAPHY')].exportSha256").value(orthographySha256))
                .andExpect(jsonPath("$.provenance.datasets[?(@.kind=='ORTHOGRAPHY')].rowCount").value(4))
                .andExpect(jsonPath("$.provenance.datasets[?(@.kind=='SEMANTIC')].batchId").value(semanticBatch.toString()))
                .andExpect(jsonPath("$.provenance.datasets[?(@.kind=='SEMANTIC')].exportSha256").value(semanticSha256))
                .andExpect(jsonPath("$.provenance.datasets[?(@.kind=='SEMANTIC')].rowCount").value(3))
                .andExpect(jsonPath("$.provenance.datasets[*].adjudicationVersion").value(org.hamcrest.Matchers.contains(1, 1)))
                .andExpect(jsonPath("$.provenance.sessionsChangedAfterExport").value(0))
                .andExpect(jsonPath("$.provenance.ppmNonInferiorityMargin").doesNotExist())
                .andExpect(jsonPath("$.provenance.tasLimit").doesNotExist())
                .andExpect(jsonPath("$.provenance.computedAt").exists());
        JsonNode results = record("results", actions);
        for (JsonNode dataset : results.get("provenance").get("datasets")) {
            assertThat(dataset.get("adjudicationSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(dataset.get("adjudicationImportId").asText()).isNotBlank();
        }
        for (String kind : List.of("ORTHOGRAPHY", "SEMANTIC")) {
            JsonNode dataset = null;
            for (JsonNode candidate : results.get("provenance").get("datasets")) {
                if (candidate.get("kind").asText().equals(kind)) {
                    dataset = candidate;
                }
            }
            String status = kind.equals("ORTHOGRAPHY") ? "orthographyAnnotation" : "semanticAnnotation";
            assertThat(dataset).isNotNull();
            assertThat(results.get(status).get("adjudicationImportId").asText())
                    .isEqualTo(dataset.get("adjudicationImportId").asText());
        }
        // F0.5 never lives in the study results.
        assertThat(results.toString()).doesNotContain("fZeroFive", "precision", "recall");

        String analysis = new String(mockMvc.perform(researcher(get(studyUrl("/analysis.csv"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn().getResponse().getContentAsByteArray(), UTF_8);
        researchBodies.add(new ResearchBody("analysis.csv", analysis, false));
        assertThat(analysis).startsWith("pseudonym,condition,task,protocol_version,included,excluded,run_id,")
                .contains("P-001,ASSISTED,TASK_A,1,true,false," + runs.get("P-001/ASSISTED") + ",120000,,4,1,")
                .contains("P-002,UNASSISTED,TASK_A,1,true,false," + runs.get("P-002/UNASSISTED") + ",120000,,4,2,")
                .contains(",ola mundo,hola mundo,0,true,")
                .contains(",ke tal,que tal,2,false,")
                .contains(",sinco amigos,cinco amigos,1,false,")
                .doesNotContain("uso normal", "T-");
    }

    // ------------------------------------------------------------------ step 6

    private void technicalEvaluations() throws Exception {
        String evaluations = RESEARCH + "/technical-evaluations";
        JsonNode created = research("record technical evaluation", researcher(post(evaluations))
                .contentType(MediaType.APPLICATION_JSON)
                .content(evaluation(0.8, 0.5, 0.7142857, 40, 10, 40)), 201);
        assertThat(created.get("scorerVersion").asText()).isEqualTo("exact_token_edits_v1");
        assertThat(created.get("datasetSha256").asText()).isEqualTo(DATASET_SHA256);
        assertThat(created.get("fZeroFive").asDouble()).isCloseTo(0.7142857, within(1e-9));
        assertThat(created.get("fOne").asDouble()).isCloseTo(0.6153846, within(1e-6));
        // Contradictory records are refused: F0.5 that does not follow P/R, and P/R that do not follow the counts.
        mockMvc.perform(researcher(post(evaluations)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(0.8, 0.5, 0.71, 40, 10, 40)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("F0.5 does not match")));
        mockMvc.perform(researcher(post(evaluations)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(0.8, 0.5, 0.7142857, 1, 0, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Precision/recall do not match TP/FP/FN"));
        mockMvc.perform(researcher(post(evaluations)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(0.8, 0.5, 0.7142857, 40, 10, 40).replace("exact_token_edits_v1", "other")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.scorerVersion").exists());
        JsonNode listed = research("list technical evaluations", researcher(get(evaluations)), 200);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(listed.get(0).get("categories")).isEmpty();
        JsonNode latest = research("latest technical evaluation", researcher(get(evaluations + "/latest")), 200);
        assertThat(latest.get("id").asText()).isEqualTo(created.get("id").asText());

        // Optional per-category breakdown (spec §11.4): validated like the global vector and returned as sent.
        String categories = ",\"categories\":[{\"category\":\"ortografia\",\"tp\":30,\"fp\":5,\"fn\":10,"
                + "\"precision\":0.857143,\"recall\":0.75,\"f05\":0.833333},"
                + "{\"category\":\"gramatica\",\"tp\":10,\"fp\":5,\"fn\":30,\"precision\":0.666667,\"recall\":0.25,"
                + "\"f05\":0.5}]}";
        JsonNode withCategories = research("record technical evaluation with categories", researcher(post(evaluations))
                .contentType(MediaType.APPLICATION_JSON)
                .content(evaluation(0.8, 0.5, 0.7142857, 40, 10, 40).replace("}", categories)), 201);
        assertThat(withCategories.get("categories")).hasSize(2);
        assertThat(withCategories.get("categories").get(0).get("category").asText()).isEqualTo("ortografia");
        assertThat(withCategories.get("categories").get(0).get("f05").asDouble()).isCloseTo(0.833333, within(1e-9));
        JsonNode latestWithCategories = research("latest technical evaluation with categories",
                researcher(get(evaluations + "/latest")), 200);
        assertThat(latestWithCategories.get("id").asText()).isEqualTo(withCategories.get("id").asText());
        assertThat(latestWithCategories.get("categories")).hasSize(2);
        // A category whose P does not follow its counts is refused; so is a malformed one (bean validation).
        mockMvc.perform(researcher(post(evaluations)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(0.8, 0.5, 0.7142857, 40, 10, 40).replace("}",
                                ",\"categories\":[{\"category\":\"ortografia\",\"tp\":30,\"fp\":5,\"fn\":10,"
                                        + "\"precision\":0.8,\"recall\":0.75,\"f05\":0.79}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Category 'ortografia': Precision/recall do not match TP/FP/FN"));
        mockMvc.perform(researcher(post(evaluations)).contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation(0.8, 0.5, 0.7142857, 40, 10, 40).replace("}",
                                ",\"categories\":[{\"category\":\"\",\"tp\":-1,\"fp\":5,\"fn\":10,"
                                        + "\"precision\":1.2,\"recall\":0.75,\"f05\":0.79}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").exists());
    }

    // ------------------------------------------------------------------ step 6b

    /** The researcher declares a technical failure on an open run, then closes data collection. */
    private void technicalFailureAndStudyClosure() throws Exception {
        JsonNode p3 = research("create participant 3", researcher(post(studyUrl("/participants"))), 201);
        UUID participant3 = UUID.fromString(p3.get("id").asText());
        JsonNode issued = research("access-code P-003", researcher(
                post(studyUrl("/participants/" + participant3 + "/access-code"))), 201, true);
        plaintextCodes.add(issued.get("code").asText());
        String runId = issued.get("runId").asText();

        mockMvc.perform(researcher(post(studyUrl("/runs/" + runId + "/technical-failure")))
                        .contentType(MediaType.APPLICATION_JSON).content(obj("reason", "corto")))
                .andExpect(status().isBadRequest());
        JsonNode failed = research("technical failure", researcher(post(studyUrl("/runs/" + runId + "/technical-failure")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(obj("reason", "La IA no respondio durante toda la sesion del piloto")), 200);
        assertThat(failed.get("status").asText()).isEqualTo("TECHNICAL_FAILURE");
        assertThat(failed.get("incidentCount").asInt()).isEqualTo(1);
        assertThat(failed.get("incidents")).hasSize(1);
        assertThat(failed.get("incidents").get(0).get("reason").asText()).isEqualTo("TECHNICAL_FAILURE");
        assertThat(failed.get("incidents").get(0).get("at").asText()).isNotBlank();
        mockMvc.perform(researcher(post(studyUrl("/runs/" + runId + "/technical-failure")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obj("reason", "Segunda declaracion posterior distinta")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only pending or active runs can fail technically"));
        // A failed run can be excluded from analysis, like a completed one.
        JsonNode excluded = research("exclude failed run", researcher(post(studyUrl("/runs/" + runId + "/exclude")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(obj("reason", "Fallo tecnico documentado por el equipo")), 200);
        assertThat(excluded.get("excluded").asBoolean()).isTrue();
        assertThat(auditRepository.findByStudyIdOrderByCreatedAtDesc(studyId))
                .extracting(event -> event.getAction()).contains("RUN_TECHNICAL_FAILURE", "RUN_EXCLUDED");

        // Closing the study ends data collection; a closed study accepts no participants, codes or protocols.
        JsonNode closed = research("close study", researcher(post(studyUrl("/close"))), 200);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        mockMvc.perform(researcher(post(studyUrl("/close")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Study is already closed"));
        mockMvc.perform(researcher(post(studyUrl("/participants")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Study is not active"));
        mockMvc.perform(researcher(post(studyUrl("/protocols"))).contentType(MediaType.APPLICATION_JSON)
                        .content(obj("taskAPrompt", "Otra consigna A", "taskBPrompt", "Otra consigna B")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Study is closed"));
        assertThat(auditRepository.findByStudyIdOrderByCreatedAtDesc(studyId))
                .extracting(event -> event.getAction()).contains("STUDY_CLOSED");
        // Results stay readable after closing.
        mockMvc.perform(researcher(get(studyUrl("/results")))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ step 7

    private void privacySweep() {
        assertThat(researchBodies).hasSizeGreaterThan(20);
        assertThat(plaintextCodes).hasSize(5).doesNotHaveDuplicates();
        List<String> identity = List.of(
                student1.id().toString(), student1.username(), student1.realName(), student1.notes(),
                student2.id().toString(), student2.username(), student2.realName(), student2.notes(),
                teacherId.toString(), teacherEmail, teacherUsername, teacherInstitution, RESEARCHER_EMAIL, RESEARCHER_PASSWORD);
        for (ResearchBody response : researchBodies) {
            String body = response.body();
            String lower = body.toLowerCase(Locale.ROOT);
            for (String secret : identity) {
                assertThat(lower).as("%s leaks %s", response.label(), secret).doesNotContain(secret.toLowerCase(Locale.ROOT));
            }
            assertThat(lower).as(response.label()).doesNotContain("studentid", "\"email", "\"notes", "institution",
                    "realname", "passwordhash", "accesscodehash");
            if (!response.carriesPlaintextCode()) {
                for (String code : plaintextCodes) {
                    assertThat(body).as("%s leaks an access code", response.label()).doesNotContain(code);
                }
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private String studyUrl(String path) {
        return RESEARCH + "/studies/" + studyId + path;
    }

    private <T extends AbstractMockHttpServletRequestBuilder<T>> T researcher(T request) {
        return bearer(researcherToken, request);
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<T>> T bearer(String token, T request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** Performs a research request, checks the status and records the body for the privacy sweep. */
    private JsonNode research(String label, AbstractMockHttpServletRequestBuilder<?> request, int expectedStatus)
            throws Exception {
        return research(label, request, expectedStatus, false);
    }

    private JsonNode research(String label, AbstractMockHttpServletRequestBuilder<?> request, int expectedStatus,
                              boolean carriesPlaintextCode) throws Exception {
        ResultActions actions = mockMvc.perform(request).andExpect(status().is(expectedStatus));
        String body = actions.andReturn().getResponse().getContentAsString();
        researchBodies.add(new ResearchBody(label, body, carriesPlaintextCode));
        return objectMapper.readTree(body);
    }

    private JsonNode record(String label, ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        researchBodies.add(new ResearchBody(label, body, false));
        return objectMapper.readTree(body);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String download(UUID batchId, String expectedSha256) throws Exception {
        var response = mockMvc.perform(researcher(get(studyUrl("/annotation-batches/" + batchId + "/export"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-SHA256", expectedSha256))
                .andReturn().getResponse();
        String csv = new String(response.getContentAsByteArray(), UTF_8);
        researchBodies.add(new ResearchBody("export " + batchId, csv, false));
        return csv;
    }

    /** Splits the blind export (the fixture texts carry no commas or quotes, so plain splitting is exact). */
    private static List<String[]> rows(String csv, String expectedHeader) {
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo(expectedHeader);
        assertThat(csv).endsWith("\n").doesNotContain("\r");
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            assertThat(cells).hasSize(expectedHeader.split(",").length);
            assertThat(cells[cells.length - 1]).as("score column is empty in the export").isEmpty();
            rows.add(cells);
        }
        return rows;
    }

    /** Builds a rater file in the minimal import layout, keeping the export's (shuffled) order. */
    private static String scored(List<String[]> rows, java.util.function.Function<String[], Integer> score) {
        StringBuilder csv = new StringBuilder("sample_code,score\n");
        rows.forEach(row -> csv.append(row[0]).append(',').append(score.apply(row)).append('\n'));
        return csv.toString();
    }

    private MockMultipartHttpServletRequestBuilder upload(UUID batchId, String slot, String rater, String csv) {
        MockMultipartHttpServletRequestBuilder builder = multipart(studyUrl("/annotation-batches/" + batchId + "/imports"))
                .file(new MockMultipartFile("file", "scores.csv", "text/csv", csv.getBytes(UTF_8)));
        builder.param("slot", slot).param("rater", rater).header(HttpHeaders.AUTHORIZATION, "Bearer " + researcherToken);
        return builder;
    }

    private static String evaluation(double p, double r, double f, int tp, int fp, int fn) {
        return String.format(Locale.ROOT,
                "{\"modelVersion\":\"%s\",\"datasetSha256\":\"%s\",\"scorerVersion\":\"exact_token_edits_v1\","
                        + "\"precision\":%s,\"recall\":%s,\"fZeroFive\":%s,\"truePositives\":%d,\"falsePositives\":%d,"
                        + "\"falseNegatives\":%d}",
                MODEL_VERSION, DATASET_SHA256, p, r, f, tp, fp, fn);
    }

    /** Small JSON object builder: alternating keys and values (strings, numbers, booleans). */
    private String obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return objectMapper.writeValueAsString(map);
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    /** Numeric closeness over any JSON number (JsonPath yields Double or BigDecimal depending on the digits). */
    private static org.hamcrest.Matcher<Object> near(double expected, double tolerance) {
        return new org.hamcrest.TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(Object item) {
                return item instanceof Number number && Math.abs(number.doubleValue() - expected) <= tolerance;
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("a number within ").appendValue(tolerance).appendText(" of ").appendValue(expected);
            }
        };
    }
}
