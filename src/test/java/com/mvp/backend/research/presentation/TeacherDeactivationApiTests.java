package com.mvp.backend.research.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

/** Baja de un docente por el investigador: sin panel para el docente ni teclado para sus alumnos; reversible. */
@SpringBootTest
@AutoConfigureMockMvc
class TeacherDeactivationApiTests {

    private static final String TEACHERS = "/api/v1/research/teachers";

    @Autowired private MockMvc mockMvc;
    @Autowired private ResearcherRepository researcherRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherStudentLinkRepository linkRepository;
    @Autowired private PersonalDataCipher cipher;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID researcherId;
    private UUID teacherId;
    private UUID studentId;
    private String email;
    private String alias;

    @BeforeEach
    void setUp() {
        researcherId = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash")).getId();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        email = "baja-" + suffix + "@c.edu";
        alias = "zorro-" + suffix;
        Teacher teacher = teacherRepository.save(new Teacher(
                "baja-" + suffix, email, null, "C", passwordEncoder.encode("Docente2026"),
                researcherId, false, "Docente Baja"));
        teacherId = teacher.getId();
        Classroom classroom = classroomRepository.save(new Classroom(teacher, "4 A"));
        Student student = studentRepository.save(new Student(alias, "C", passwordEncoder.encode("1234")));
        studentId = student.getId();
        linkRepository.save(new TeacherStudentLink(teacher, student, classroom, cipher.encrypt("Nombre"), null));
    }

    @Test
    void deactivatedTeacherAndTheirStudentsLoseAccessUntilReactivated() throws Exception {
        mockMvc.perform(post(TEACHERS + "/{id}/deactivate", teacherId).with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivatedAt").isNotEmpty());
        mockMvc.perform(post(TEACHERS + "/{id}/deactivate", teacherId).with(researcher()))
                .andExpect(status().isConflict());

        // Ni login ni sesion abierta para el docente.
        mockMvc.perform(staffLogin(email, "Docente2026")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/teachers/classrooms").with(teacher()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Teacher account is deactivated"));
        // Ni login ni sesion abierta para sus alumnos.
        mockMvc.perform(studentLogin()).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/students/me").with(student())).andExpect(status().isUnauthorized());
        // El directorio lo marca y no se le puede reiniciar la contrasena.
        mockMvc.perform(get("/api/v1/research/classrooms").with(researcher()))
                .andExpect(jsonPath("$[?(@.teacherId=='" + teacherId + "')].teacherDeactivated").value(true));
        mockMvc.perform(post(TEACHERS + "/{id}/reset-password", teacherId).with(researcher()))
                .andExpect(status().isConflict());

        mockMvc.perform(post(TEACHERS + "/{id}/reactivate", teacherId).with(researcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivatedAt").doesNotExist());
        mockMvc.perform(post(TEACHERS + "/{id}/reactivate", teacherId).with(researcher()))
                .andExpect(status().isConflict());
        mockMvc.perform(staffLogin(email, "Docente2026")).andExpect(status().isOk());
        mockMvc.perform(studentLogin()).andExpect(status().isOk());
    }

    @Test
    void onlyTheResearcherCanDeactivateTeachers() throws Exception {
        mockMvc.perform(post(TEACHERS + "/{id}/deactivate", teacherId).with(teacher()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(TEACHERS + "/{id}/deactivate", UUID.randomUUID()).with(researcher()))
                .andExpect(status().isNotFound());
    }

    private RequestBuilder staffLogin(String login, String password) {
        return post("/api/v1/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + login + "\",\"password\":\"" + password + "\"}");
    }

    private RequestBuilder studentLogin() {
        return post("/api/v1/auth/students/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + alias + "\",\"password\":\"1234\"}");
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
