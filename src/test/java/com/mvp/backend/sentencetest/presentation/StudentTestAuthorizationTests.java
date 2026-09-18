package com.mvp.backend.sentencetest.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.SentenceTestRepository;
import com.mvp.backend.sentencetest.domain.repository.TestAssignmentRepository;
import com.mvp.backend.sentencetest.domain.repository.TestSentenceRepository;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class StudentTestAuthorizationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearcherRepository researcherRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SentenceTestRepository testRepository;
    @Autowired private TestSentenceRepository sentenceRepository;
    @Autowired private TestAssignmentRepository assignmentRepository;

    private UUID testId;
    private UUID studentId;
    private UUID otherStudentId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.parse("2026-09-20T10:00:00Z");
        Researcher researcher = researcherRepository.save(new Researcher(suffix + "@tesis.edu.pe", "hash"));
        SentenceTest test = new SentenceTest("PRUEBA-" + suffix, "Dictado " + suffix, researcher.getId());
        test.activate(2, now);
        test = testRepository.save(test);
        sentenceRepository.save(new TestSentence(test, 1, SentenceKind.DICTATED, "El perro corre.", Assistance.ASSISTED));
        sentenceRepository.save(new TestSentence(test, 2, SentenceKind.FREE, "Una oracion sobre tu mascota", Assistance.UNASSISTED));
        Student student = studentRepository.save(new Student("tigre-" + suffix, "Colegio", "hash"));
        Student other = studentRepository.save(new Student("puma-" + suffix, "Colegio", "hash"));
        assignmentRepository.save(new TestAssignment(test, student, null, researcher.getId(), now));
        testId = test.getId();
        studentId = student.getId();
        otherStudentId = other.getId();
    }

    @Test
    void assignedTestsArePendingForStudentAndForbiddenForTeacher() throws Exception {
        mockMvc.perform(get("/api/v1/tests/assigned").with(student(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].testId").value(testId.toString()))
                .andExpect(jsonPath("$[0].sentenceCount").value(2))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        mockMvc.perform(get("/api/v1/tests/assigned")
                .with(jwt().jwt(t -> t.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void startAttemptCreatesThenReturnsSameAttemptWithoutReferenceText() throws Exception {
        String body = mockMvc.perform(startAttempt(studentId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testId").value(testId.toString()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.nextPosition").value(1))
                .andExpect(jsonPath("$.sentences.length()").value(2))
                .andExpect(jsonPath("$.sentences[0].position").value(1))
                .andExpect(jsonPath("$.sentences[0].assistance").value("ASSISTED"))
                .andExpect(jsonPath("$.sentences[1].assistance").value("UNASSISTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("perro").doesNotContain("mascota").doesNotContain("reference").doesNotContain("DICTATED");
        String attemptId = objectMapper.readTree(body).get("attemptId").asText();

        mockMvc.perform(startAttempt(studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId));

        mockMvc.perform(get("/api/v1/tests/assigned").with(student(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"));

        mockMvc.perform(startAttempt(otherStudentId))
                .andExpect(status().isForbidden());
    }

    @Test
    void sentencesFollowOrderAndFinishIsIdempotentByCompletionKey() throws Exception {
        String attemptId = newAttemptId();

        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 2).with(student(studentId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 1).with(student(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseId").isString())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.assistance").value("ASSISTED"))
                .andExpect(jsonPath("$.alreadyStarted").value(false));

        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 1).with(student(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyStarted").value(true));

        UUID key = UUID.randomUUID();
        String first = mockMvc.perform(finishSentence(attemptId, 1, finishBody("El pero corre", 500, 4000, false, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseId").isString())
                .andExpect(jsonPath("$.nextPosition").value(2))
                .andExpect(jsonPath("$.attemptStatus").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();

        String again = mockMvc.perform(finishSentence(attemptId, 1, finishBody("El pero corre", 500, 4000, false, key)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(again)).isEqualTo(objectMapper.readTree(first));

        mockMvc.perform(finishSentence(attemptId, 1, finishBody("otro texto", 500, 4000, false, UUID.randomUUID())))
                .andExpect(status().isConflict());
    }

    @Test
    void skippingLastSentenceCompletesAttemptAndCompletedAttemptCannotBeCancelled() throws Exception {
        String attemptId = newAttemptId();
        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 1).with(student(studentId)))
                .andExpect(status().isOk());
        mockMvc.perform(finishSentence(attemptId, 1, finishBody("El perro corre.", 500, 4000, false, UUID.randomUUID())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 2).with(student(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistance").value("UNASSISTED"));

        mockMvc.perform(finishSentence(attemptId, 2, finishBody("", null, 3000, true, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextPosition").doesNotExist())
                .andExpect(jsonPath("$.attemptStatus").value("COMPLETED"));

        mockMvc.perform(get("/api/v1/tests/assigned").with(student(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/attempts/{id}/cancel", attemptId).with(student(studentId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABANDONED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void completedTestCannotBeStartedAgain() throws Exception {
        String attemptId = newAttemptId();
        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 1).with(student(studentId)))
                .andExpect(status().isOk());
        mockMvc.perform(finishSentence(attemptId, 1, finishBody("El perro corre.", 500, 4000, false, UUID.randomUUID())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 2).with(student(studentId)))
                .andExpect(status().isOk());
        mockMvc.perform(finishSentence(attemptId, 2, finishBody("", null, 3000, true, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptStatus").value("COMPLETED"));

        mockMvc.perform(startAttempt(studentId))
                .andExpect(status().isConflict());
    }

    @Test
    void attemptInProgressCanBeCancelledAndAnotherStudentCannotTouchIt() throws Exception {
        String attemptId = newAttemptId();

        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 1).with(student(otherStudentId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/attempts/{id}/cancel", attemptId).with(student(studentId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"TECHNICAL_PROBLEM\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/attempts/{id}/responses/{position}/start", attemptId, 1).with(student(studentId)))
                .andExpect(status().isConflict());
    }

    private String newAttemptId() throws Exception {
        String body = mockMvc.perform(startAttempt(studentId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("attemptId").asText();
    }

    private org.springframework.test.web.servlet.RequestBuilder startAttempt(UUID student) {
        return post("/api/v1/tests/{id}/attempts", testId).with(student(student))
                .contentType(MediaType.APPLICATION_JSON).content("{\"appVersion\":\"keyboard-test\"}");
    }

    private org.springframework.test.web.servlet.RequestBuilder finishSentence(String attemptId, int position, String body) {
        return put("/api/v1/attempts/{id}/responses/{position}", attemptId, position).with(student(studentId))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String finishBody(String text, Integer firstKeyOffsetMs, int finishedOffsetMs, boolean skipped, UUID key) {
        return "{\"finalText\":\"" + text + "\",\"firstKeyOffsetMs\":" + firstKeyOffsetMs
                + ",\"finishedOffsetMs\":" + finishedOffsetMs
                + ",\"suggestionsOffered\":1,\"suggestionsAccepted\":0,\"suggestionsRejected\":1,\"suggestionsUndone\":0"
                + ",\"skipped\":" + skipped + ",\"completionKey\":\"" + key + "\"}";
    }

    private static RequestPostProcessor student(UUID id) {
        return jwt().jwt(t -> t.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"));
    }
}
