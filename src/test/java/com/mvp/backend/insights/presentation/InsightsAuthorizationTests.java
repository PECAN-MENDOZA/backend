package com.mvp.backend.insights.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.model.WordCorrection;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

/** Panel del docente: solo el dueno del salon lo consulta y ve el nombre real de sus alumnos. */
@SpringBootTest
@AutoConfigureMockMvc
class InsightsAuthorizationTests {

    private static final String BASE = "/api/v1/teachers/classrooms/{id}";

    @Autowired private MockMvc mockMvc;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherStudentLinkRepository linkRepository;
    @Autowired private CorrectionSessionRepository sessionRepository;
    @Autowired private WordCorrectionRepository wordCorrectionRepository;
    @Autowired private PersonalDataCipher cipher;

    private UUID teacherId;
    private UUID otherTeacherId;
    private UUID classroomId;
    private UUID studentId;
    private UUID sessionId;

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
        linkRepository.save(new TeacherStudentLink(teacher, student, classroom, cipher.encrypt("Ana Pérez"), null));

        CorrectionSession session = new CorrectionSession(student, "el camion rojo");
        session.complete("el camión rojo", 1, "[]", 120L);
        session.registerFeedback("el camión rojo", null, true, 1, null);
        session = sessionRepository.save(session);
        sessionId = session.getId();
        wordCorrectionRepository.save(new WordCorrection(session, "camion", "camión", 3, 9));
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
