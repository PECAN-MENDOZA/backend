package com.mvp.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

import tools.jackson.databind.ObjectMapper;

/** Contrato 1, 3 y 4: baja sin acceso, reiniciar PIN cierra sesiones y "ultimo acceso" real. */
@SpringBootTest
@AutoConfigureMockMvc
class StudentAccessFilterTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherStudentLinkRepository linkRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PersonalDataCipher cipher;

    private UUID teacherId;
    private UUID classroomId;
    private UUID studentId;
    private String alias;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Teacher teacher = teacherRepository.save(new Teacher(
                "doc-" + suffix, suffix + "@c.edu", null, "Colegio", passwordEncoder.encode("Docente2026")));
        teacherId = teacher.getId();
        Classroom classroom = classroomRepository.save(new Classroom(teacher, "4.º A"));
        classroomId = classroom.getId();
        alias = "tren-" + suffix;
        Student student = studentRepository.save(new Student(alias, "Colegio", passwordEncoder.encode("1234")));
        studentId = student.getId();
        linkRepository.save(new TeacherStudentLink(teacher, student, classroom, cipher.encrypt("Mateo"), null));
    }

    @Test
    void deactivatedStudentCannotLogInNorKeepUsingAnOpenSession() throws Exception {
        mockMvc.perform(get("/api/v1/students/me").with(student(Instant.now()))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/teachers/students/{id}/deactivate", studentId).with(teacher()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/students/me").with(student(Instant.now())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(post("/api/v1/corrections/process").with(student(Instant.now()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texto_original\":\"ola\"}"))
                .andExpect(status().isUnauthorized());
        // Con el PIN correcto se explica el motivo; con uno errado, el mensaje generico.
        mockMvc.perform(studentLogin(alias, "1234"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Student account is deactivated"));
        mockMvc.perform(studentLogin(alias, "9999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid student credentials"));

        // Reactivado vuelve a entrar.
        mockMvc.perform(post("/api/v1/teachers/students/{id}/reactivate", studentId).with(teacher()))
                .andExpect(status().isOk());
        mockMvc.perform(studentLogin(alias, "1234")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/students/me").with(student(Instant.now()))).andExpect(status().isOk());
    }

    @Test
    void resetPinRejectsTokensIssuedBeforeItComparingSeconds() throws Exception {
        String body = mockMvc.perform(post("/api/v1/teachers/students/{id}/reset-pin", studentId).with(teacher()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newPin = objectMapper.readTree(body).get("pin").asString();
        Instant changedAt = studentRepository.findById(studentId).orElseThrow().getCredentialsChangedAt();
        assertThat(changedAt).isNotNull();

        mockMvc.perform(get("/api/v1/students/me").with(student(changedAt.minusSeconds(5))))
                .andExpect(status().isUnauthorized());
        // Mismo segundo (el iat no tiene fraccion): sigue valido.
        mockMvc.perform(get("/api/v1/students/me").with(student(Instant.ofEpochSecond(changedAt.getEpochSecond()))))
                .andExpect(status().isOk());

        // Un token real emitido con el PIN nuevo funciona.
        String login = mockMvc.perform(studentLogin(alias, newPin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).get("token").asString();
        mockMvc.perform(get("/api/v1/students/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void lastAccessMovesOnlyWhenTheStudentLogsIn() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/students").with(teacher())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/teachers/classrooms/{id}/students", classroomId).with(teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastAccessAt").doesNotExist());
        mockMvc.perform(get("/api/v1/kpis/students/{id}/summary", studentId).with(teacher()))
                .andExpect(status().isOk());
        assertThat(linkRepository.findByStudentIdAndDeletedAtIsNull(studentId).get(0).getLastAccessAt()).isNull();

        mockMvc.perform(studentLogin(alias, "1234")).andExpect(status().isOk());

        assertThat(linkRepository.findByStudentIdAndDeletedAtIsNull(studentId).get(0).getLastAccessAt()).isNotNull();
    }

    private RequestPostProcessor student(Instant issuedAt) {
        return jwt().jwt(token -> token.subject(studentId.toString()).issuedAt(issuedAt))
                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"));
    }

    private RequestPostProcessor teacher() {
        return jwt().jwt(token -> token.subject(teacherId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }

    private static org.springframework.test.web.servlet.RequestBuilder studentLogin(String username, String pin) {
        return post("/api/v1/auth/students/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + pin + "\"}");
    }
}
