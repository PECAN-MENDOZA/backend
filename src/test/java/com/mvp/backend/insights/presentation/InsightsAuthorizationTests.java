package com.mvp.backend.insights.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.model.WordCorrection;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.sentencetest.application.service.SentenceAligner;
import com.mvp.backend.sentencetest.domain.model.Assistance;
import com.mvp.backend.sentencetest.domain.model.SentenceKind;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.SentenceTestRepository;
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

/** Panel del docente: solo el dueno del salon o del vinculo lo consulta y ve el nombre real de sus alumnos. */
@SpringBootTest
@AutoConfigureMockMvc
class InsightsAuthorizationTests {

    private static final String BASE = "/api/v1/teachers/classrooms/{id}";
    private static final String STUDENT = "/api/v1/teachers/students/{id}";
    private static final String LIVE = "/api/v1/teachers/tests/live";
    private static final String PDF = "/api/v1/reports/students/{id}/pdf";

    @Autowired private MockMvc mockMvc;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherStudentLinkRepository linkRepository;
    @Autowired private CorrectionSessionRepository sessionRepository;
    @Autowired private WordCorrectionRepository wordCorrectionRepository;
    @Autowired private SentenceTestRepository testRepository;
    @Autowired private TestSentenceRepository sentenceRepository;
    @Autowired private TestAttemptRepository attemptRepository;
    @Autowired private TestResponseRepository responseRepository;
    @Autowired private PersonalDataCipher cipher;

    private UUID teacherId;
    private UUID otherTeacherId;
    private UUID classroomId;
    private UUID studentId;
    private String studentUsername;
    private UUID sessionId;
    private UUID completedAttemptId;
    private UUID liveAttemptId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Teacher teacher = teacherRepository.save(newTeacher());
        otherTeacherId = teacherRepository.save(newTeacher()).getId();
        teacherId = teacher.getId();
        Classroom classroom = classroomRepository.save(new Classroom(teacher, "3.º B"));
        classroomId = classroom.getId();
        Student student = studentRepository.save(new Student("alumno-" + suffix, "Colegio", "hash"));
        studentId = student.getId();
        studentUsername = student.getUsername();
        linkRepository.save(new TeacherStudentLink(teacher, student, classroom, cipher.encrypt("Ana Pérez"), null));

        CorrectionSession session = new CorrectionSession(student, "el camion rojo");
        session.complete("el camión rojo", 1, "[]", 120L);
        session.registerFeedback("el camión rojo", null, true, 1, null);
        session = sessionRepository.save(session);
        sessionId = session.getId();
        wordCorrectionRepository.save(new WordCorrection(session, "camion", "camión", 3, 9));

        // Un intento terminado (con una oracion dictada corregida por el alineador) y otro en curso.
        SentenceTest test = testRepository.save(new SentenceTest("PRUEBA-" + suffix, "Dictado", UUID.randomUUID()));
        TestSentence first = sentenceRepository.save(
                new TestSentence(test, 1, SentenceKind.DICTATED, "La vaca come.", Assistance.ASSISTED));
        sentenceRepository.save(new TestSentence(test, 2, SentenceKind.FREE, "Escribe algo", Assistance.UNASSISTED));
        Instant start = Instant.parse("2026-09-17T14:00:00Z");
        TestAttempt completed = new TestAttempt(test, student, "app-1", "backend-1", start);
        TestResponse response = new TestResponse(completed, first, start);
        response.finish("La baca come", 500L, 4000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(),
                start.plusSeconds(4));
        SentenceAligner.Alignment alignment = SentenceAligner.align("La vaca come.", "La baca come");
        response.recordAutoErrors(alignment.errorCount(), alignment.toJson());
        completed.complete(start.plusSeconds(60));
        completedAttemptId = attemptRepository.save(completed).getId();
        responseRepository.save(response);

        TestAttempt live = attemptRepository.save(
                new TestAttempt(test, student, "app-1", "backend-1", start.plusSeconds(600)));
        liveAttemptId = live.getId();
        TestResponse liveResponse = new TestResponse(live, first, start.plusSeconds(600));
        liveResponse.finish("La vaca come", 100L, 4000L, false, TestResponse.Counters.ZERO, UUID.randomUUID(),
                start.plusSeconds(605));
        responseRepository.save(liveResponse);
    }

    @Test
    void teacherSeesOwnClassroomActivityWithDecryptedRealName() throws Exception {
        mockMvc.perform(get(BASE + "/activity", classroomId).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classroomId").value(classroomId.toString()))
                .andExpect(jsonPath("$.classroomName").value("3.º B"))
                .andExpect(jsonPath("$.students.length()").value(1))
                .andExpect(jsonPath("$.students[0].studentId").value(studentId.toString()))
                .andExpect(jsonPath("$.students[0].realName").value("Ana Pérez"))
                .andExpect(jsonPath("$.students[0].correctionsInPeriod").value(1))
                .andExpect(jsonPath("$.students[0].lastActivityAt").isString())
                .andExpect(jsonPath("$.students[0].outcomes.accepted").value(1));
    }

    @Test
    void teacherSeesRecentCorrectionsAndErrorsOfOwnClassroom() throws Exception {
        mockMvc.perform(get(BASE + "/corrections/recent", classroomId).param("limit", "5").with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$[0].realName").value("Ana Pérez"))
                .andExpect(jsonPath("$[0].finalText").value("el camión rojo"))
                .andExpect(jsonPath("$[0].outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$[0].outcomeLabel").value("Aceptó"))
                .andExpect(jsonPath("$[0].inTest").value(false));

        mockMvc.perform(get(BASE + "/errors", classroomId).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.types[0].type").value("TILDE"))
                .andExpect(jsonPath("$.types[0].topWords[0].original").value("camion"))
                .andExpect(jsonPath("$.types[0].topWords[0].corrected").value("camión"));
    }

    @Test
    void anotherTeacherCannotSeeTheClassroom() throws Exception {
        mockMvc.perform(get(BASE + "/activity", classroomId).with(teacher(otherTeacherId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/corrections/recent", classroomId).with(teacher(otherTeacherId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/errors", classroomId).with(teacher(otherTeacherId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherSeesOwnStudentErrorsHelpWritingsAndTests() throws Exception {
        mockMvc.perform(get(STUDENT + "/errors", studentId).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value(studentId.toString()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.types[0].type").value("TILDE"))
                .andExpect(jsonPath("$.types[0].examples[0].original").value("camion"))
                .andExpect(jsonPath("$.practiceWords.length()").value(0));

        mockMvc.perform(get(STUDENT + "/help", studentId).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.acceptedPct").value(100.0))
                .andExpect(jsonPath("$.editedPct").value(0.0));

        mockMvc.perform(get(STUDENT + "/writings", studentId).param("limit", "10").with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$[0].finalText").value("el camión rojo"))
                .andExpect(jsonPath("$[0].outcomeLabel").value("Aceptó"))
                .andExpect(jsonPath("$[0].inTest").value(false))
                .andExpect(jsonPath("$[0].testCode").doesNotExist());

        mockMvc.perform(get(STUDENT + "/tests", studentId).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attemptId").value(completedAttemptId.toString()))
                .andExpect(jsonPath("$[0].excluded").value(false))
                .andExpect(jsonPath("$[0].sentences.length()").value(1))
                .andExpect(jsonPath("$[0].sentences[0].errorCount").value(1))
                .andExpect(jsonPath("$[0].sentences[0].errorSource").value("AUTO"))
                .andExpect(jsonPath("$[0].sentences[0].edits[0].type").value("SUSTITUCION"))
                .andExpect(jsonPath("$[0].sentences[0].edits[0].expected").value("vaca"))
                .andExpect(jsonPath("$[0].sentences[0].edits[0].written").value("baca"));
    }

    @Test
    void anotherTeacherCannotSeeTheStudent() throws Exception {
        for (String path : new String[] {"/errors", "/help", "/writings", "/tests"}) {
            mockMvc.perform(get(STUDENT + path, studentId).with(teacher(otherTeacherId)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get(PDF, studentId).with(teacher(otherTeacherId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void liveTestsShowOnlyTheTeachersOwnStudents() throws Exception {
        mockMvc.perform(get(LIVE).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attemptId").value(liveAttemptId.toString()))
                .andExpect(jsonPath("$[0].studentId").value(studentId.toString()))
                .andExpect(jsonPath("$[0].realName").value("Ana Pérez"))
                .andExpect(jsonPath("$[0].classroomId").value(classroomId.toString()))
                .andExpect(jsonPath("$[0].classroomName").value("3.º B"))
                .andExpect(jsonPath("$[0].currentPosition").value(2))
                .andExpect(jsonPath("$[0].sentenceCount").value(2));

        mockMvc.perform(get(LIVE).with(teacher(otherTeacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void periodReportPdfIsDownloadedAsAttachment() throws Exception {
        MvcResult result = mockMvc.perform(get(PDF, studentId)
                .param("from", "2026-09-10").param("to", "2026-09-17").with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"reporte-" + studentUsername + "-2026-09-10_2026-09-17.pdf\""))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void reportAvailabilityEndpointNoLongerExists() throws Exception {
        mockMvc.perform(get("/api/v1/reports/students/{id}", studentId).param("month", "2026-09")
                .with(teacher(teacherId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void researcherCannotUseTeacherInsights() throws Exception {
        mockMvc.perform(get(BASE + "/activity", classroomId)
                .with(jwt().jwt(t -> t.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void invertedPeriodIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/activity", classroomId)
                .param("from", "2026-09-17").param("to", "2026-09-10").with(teacher(teacherId)))
                .andExpect(status().isBadRequest());
    }

    private static RequestPostProcessor teacher(UUID id) {
        return jwt().jwt(t -> t.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }

    private static Teacher newTeacher() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new Teacher("docente-" + suffix, suffix + "@colegio.edu.pe", null, "Colegio", "hash");
    }
}
