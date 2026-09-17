package com.mvp.backend.sentencetest.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAssignment;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.SentenceTestRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAssignmentRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestResponseRepository;
import com.mvp.backend.sentencetest.domain.repository.TestSentenceRepository;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ResearchTestApiTests {

    private static final String THREE_SENTENCES = """
            {"title":"Dictado","notes":"n","sentences":[
              {"kind":"DICTATED","referenceText":"El perro corre.","assistance":"ASSISTED"},
              {"kind":"DICTATED","referenceText":"La casa es azul.","assistance":"UNASSISTED"},
              {"kind":"FREE","referenceText":"Escribe sobre tu mascota","assistance":"ASSISTED"}]}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearcherRepository researcherRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherStudentLinkRepository linkRepository;
    @Autowired private SentenceTestRepository testRepository;
    @Autowired private TestSentenceRepository sentenceRepository;
    @Autowired private TestAssignmentRepository assignmentRepository;
    @Autowired private TestAttemptRepository attemptRepository;
    @Autowired private TestResponseRepository responseRepository;
    @Autowired private PersonalDataCipher cipher;

    private final Instant now = Instant.parse("2026-09-20T10:00:00Z");
    private String suffix;
    private UUID researcherId;
    private UUID teacherId;
    private UUID classroomId;
    private UUID studentId;
    private UUID otherTestId;
    private UUID otherAttemptId;
    private UUID dictatedResponseId;
    private UUID freeResponseId;

    @BeforeEach
    void setUp() {
        // Sufijo en mayusculas: los codigos de prueba solo admiten [A-Z0-9-].
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        researcherId = researcherRepository.save(new Researcher(suffix + "@lab.edu", "hash")).getId();
        Teacher teacher = teacherRepository.save(new Teacher("doc-" + suffix, suffix + "@c.edu", null, "C", "hash", researcherId, true));
        teacherId = teacher.getId();
        Classroom classroom = classroomRepository.save(new Classroom(teacher, "3 B"));
        classroomId = classroom.getId();
        Student student = studentRepository.save(new Student("tigre-" + suffix, "Colegio", "hash"));
        studentId = student.getId();
        linkRepository.save(new TeacherStudentLink(teacher, student, classroom, cipher.encrypt("Nombre Real Secreto"), null));

        // Otra prueba, ya completada por el alumno, para los casos de intento, exclusion y anotacion.
        SentenceTest other = new SentenceTest("OTRA-" + suffix, "Otra", researcherId);
        other.activate(2, now);
        other = testRepository.save(other);
        otherTestId = other.getId();
        TestSentence dictated = sentenceRepository.save(
                new TestSentence(other, 1, SentenceKind.DICTATED, "El perro corre.", Assistance.ASSISTED));
        TestSentence free = sentenceRepository.save(
                new TestSentence(other, 2, SentenceKind.FREE, "Escribe sobre tu mascota", Assistance.UNASSISTED));
        assignmentRepository.save(new TestAssignment(other, student, null, researcherId, now));
        TestAttempt attempt = new TestAttempt(other, student, "app-1", "backend-1", now);
        TestResponse first = new TestResponse(attempt, dictated, now);
        first.finish("El pero corre.", 100L, 4000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(), now);
        first.recordAutoErrors(1, "{\"word_count\":3,\"error_count\":1}");
        TestResponse second = new TestResponse(attempt, free, now);
        second.finish("Mi gato duerme mucho", 200L, 9000L, false, new TestResponse.Counters(2, 1, 0, 0), UUID.randomUUID(), now);
        attempt.complete(now);
        otherAttemptId = attemptRepository.save(attempt).getId();
        dictatedResponseId = responseRepository.save(first).getId();
        freeResponseId = responseRepository.save(second).getId();
    }

    @Test
    void researcherWritesActivatesAndAssignsATest() throws Exception {
        String code = "PRUEBA-" + suffix;
        String created = mockMvc.perform(post("/api/v1/research/tests").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"title\":\"Dictado\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sentenceCount").value(0))
                .andReturn().getResponse().getContentAsString();
        String testId = objectMapper.readTree(created).get("id").asString();

        mockMvc.perform(put("/api/v1/research/tests/" + testId).with(researcher())
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_SENTENCES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentenceCount").value(3))
                .andExpect(jsonPath("$.counts.dictated").value(2))
                .andExpect(jsonPath("$.counts.free").value(1))
                .andExpect(jsonPath("$.counts.assisted").value(2))
                .andExpect(jsonPath("$.counts.unassisted").value(1))
                .andExpect(jsonPath("$.sentences[2].position").value(3))
                .andExpect(jsonPath("$.sentences[2].kind").value("FREE"))
                .andExpect(jsonPath("$.notes").value("n"));

        // Segundo PUT sobre el borrador: reemplaza las oraciones existentes (indice unico test_id+position).
        mockMvc.perform(put("/api/v1/research/tests/" + testId).with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Dictado v2","sentences":[
                                  {"kind":"FREE","referenceText":"Escribe sobre tu mascota","assistance":"UNASSISTED"},
                                  {"kind":"DICTATED","referenceText":"El perro corre.","assistance":"ASSISTED"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Dictado v2"))
                .andExpect(jsonPath("$.sentenceCount").value(2))
                .andExpect(jsonPath("$.sentences[0].position").value(1))
                .andExpect(jsonPath("$.sentences[0].kind").value("FREE"))
                .andExpect(jsonPath("$.sentences[1].position").value(2))
                .andExpect(jsonPath("$.counts.assisted").value(1));
        assertThat(sentenceRepository.countByTestId(UUID.fromString(testId))).isEqualTo(2);

        mockMvc.perform(put("/api/v1/research/tests/" + testId).with(researcher())
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_SENTENCES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentenceCount").value(3));

        mockMvc.perform(post("/api/v1/research/tests/" + testId + "/activate").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(put("/api/v1/research/tests/" + testId).with(researcher())
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_SENTENCES))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/research/tests/" + testId + "/assignments").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + studentId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].studentId").value(studentId.toString()))
                .andExpect(jsonPath("$[0].studentUsername").value("tigre-" + suffix))
                .andExpect(jsonPath("$[0].attemptStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].sentenceCount").value(3))
                .andExpect(jsonPath("$[0].excluded").value(false))
                .andExpect(content().string(not(containsString("studentRealName"))))
                .andExpect(content().string(not(containsString("Nombre Real Secreto"))));

        // Idempotente: repetir la asignacion no duplica filas.
        mockMvc.perform(post("/api/v1/research/tests/" + testId + "/assignments").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + studentId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/research/tests").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + code + "')].assignedCount").value(1))
                .andExpect(jsonPath("$[?(@.code=='" + code + "')].completedCount").value(0));

        mockMvc.perform(post("/api/v1/research/tests/" + testId + "/close").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void assignByClassroomUsesActiveLinksAndUnknownClassroomIsNotFound() throws Exception {
        UUID testId = activeTest();

        mockMvc.perform(post("/api/v1/research/tests/" + testId + "/assignments").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classroomId\":\"" + classroomId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].classroomId").value(classroomId.toString()))
                .andExpect(jsonPath("$[0].studentUsername").value("tigre-" + suffix));

        mockMvc.perform(post("/api/v1/research/tests/" + testId + "/assignments").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classroomId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/research/tests/" + testId + "/assignments").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidAndDuplicateCodesAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/research/tests").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"prueba x\",\"title\":\"Dictado\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/research/tests").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"OTRA-" + suffix + "\",\"title\":\"Dictado\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void teacherAndStudentAreForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/research/tests").with(teacher()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/research/tests").with(student()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/research/tests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void attemptDetailIsScopedToItsTest() throws Exception {
        UUID testId = activeTest();

        mockMvc.perform(get("/api/v1/research/tests/" + testId + "/attempts/" + otherAttemptId).with(researcher()))
                .andExpect(status().isNotFound());

        String body = mockMvc.perform(get("/api/v1/research/tests/" + otherTestId + "/attempts/" + otherAttemptId).with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(otherAttemptId.toString()))
                .andExpect(jsonPath("$.studentUsername").value("tigre-" + suffix))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.appVersion").value("app-1"))
                .andExpect(jsonPath("$.responses.length()").value(2))
                .andExpect(jsonPath("$.responses[0].kind").value("DICTATED"))
                .andExpect(jsonPath("$.responses[0].wordCount").value(3))
                .andExpect(jsonPath("$.responses[0].autoErrorCount").value(1))
                .andExpect(jsonPath("$.responses[0].effectiveErrorCount").value(1))
                .andExpect(jsonPath("$.responses[0].errorSource").value("AUTO"))
                .andExpect(jsonPath("$.responses[0].durationFromFirstKeyMs").value(3900))
                .andExpect(jsonPath("$.responses[1].kind").value("FREE"))
                .andExpect(jsonPath("$.responses[1].wordCount").value(4))
                .andExpect(jsonPath("$.responses[1].errorSource").value("PENDING"))
                .andExpect(jsonPath("$.responses[1].suggestionsOffered").value(2))
                .andExpect(content().string(not(containsString("studentRealName"))))
                .andExpect(content().string(not(containsString("Nombre Real Secreto"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode detail = objectMapper.readTree(body).get("responses").get(0).get("autoErrorDetail");
        assertThat(detail.isString()).isTrue();
        assertThat(objectMapper.readTree(detail.asString()).get("error_count").asInt()).isEqualTo(1);
    }

    @Test
    void annotationOnlyAppliesToFreeSentences() throws Exception {
        mockMvc.perform(put("/api/v1/research/responses/" + dictatedResponseId + "/annotation").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"errorCount\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Annotation only applies to free sentences"));

        mockMvc.perform(put("/api/v1/research/responses/" + freeResponseId + "/annotation").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"errorCount\":-1}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/research/responses/" + freeResponseId + "/annotation").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"errorCount\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseId").value(freeResponseId.toString()))
                .andExpect(jsonPath("$.annotatedErrorCount").value(2))
                .andExpect(jsonPath("$.effectiveErrorCount").value(2))
                .andExpect(jsonPath("$.errorSource").value("ANNOTATED"));

        TestResponse stored = responseRepository.findById(freeResponseId).orElseThrow();
        assertThat(stored.getAnnotatedBy()).isEqualTo(researcherId);
    }

    @Test
    void exclusionRequiresAReasonAndIsReflectedInAssignments() throws Exception {
        String base = "/api/v1/research/tests/" + otherTestId + "/attempts/" + otherAttemptId + "/exclude";
        mockMvc.perform(post(base).with(researcher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"corto\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(base).with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Interrumpido por el timbre del recreo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedAt").exists())
                .andExpect(jsonPath("$.exclusionReason").value("Interrumpido por el timbre del recreo"));

        mockMvc.perform(get("/api/v1/research/tests/" + otherTestId + "/assignments").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptId").value(otherAttemptId.toString()))
                .andExpect(jsonPath("$[0].attemptStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[0].currentPosition").doesNotExist())
                .andExpect(jsonPath("$[0].excluded").value(true));

        mockMvc.perform(get("/api/v1/research/tests/" + otherTestId).with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(1))
                .andExpect(jsonPath("$.assignedCount").value(1));
    }

    private UUID activeTest() {
        SentenceTest test = new SentenceTest("ACT-" + suffix, "Activa", researcherId);
        test.activate(1, now);
        test = testRepository.save(test);
        sentenceRepository.save(new TestSentence(test, 1, SentenceKind.DICTATED, "Hola mundo.", Assistance.ASSISTED));
        return test.getId();
    }

    private RequestPostProcessor researcher() {
        return jwt().jwt(token -> token.subject(researcherId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER"));
    }

    private RequestPostProcessor teacher() {
        return jwt().jwt(token -> token.subject(teacherId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }

    private RequestPostProcessor student() {
        return jwt().jwt(token -> token.subject(studentId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"));
    }
}
