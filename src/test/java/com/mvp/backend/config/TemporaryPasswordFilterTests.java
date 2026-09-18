package com.mvp.backend.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

/** Contrasena temporal aplicada en el servidor: solo se permite cambiarla hasta que el docente lo haga. */
@SpringBootTest
@AutoConfigureMockMvc
class TemporaryPasswordFilterTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID temporaryTeacherId;
    private UUID settledTeacherId;

    @BeforeEach
    void setUp() {
        temporaryTeacherId = teacherRepository.save(newTeacher(true)).getId();
        settledTeacherId = teacherRepository.save(newTeacher(false)).getId();
    }

    @Test
    void teacherWithTemporaryPasswordIsBlockedUntilChanged() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/classrooms").with(teacher(temporaryTeacherId)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Password change required"));
        mockMvc.perform(get("/api/v1/kpis/students/" + UUID.randomUUID() + "/summary")
                        .with(teacher(temporaryTeacherId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Password change required"));

        mockMvc.perform(post("/api/v1/auth/teachers/change-password")
                        .with(teacher(temporaryTeacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Temporary23\",\"newPassword\":\"Permanent45\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/teachers/classrooms").with(teacher(temporaryTeacherId)))
                .andExpect(status().isOk());
    }

    @Test
    void teacherWithOwnPasswordIsNotAffected() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/classrooms").with(teacher(settledTeacherId)))
                .andExpect(status().isOk());
    }

    @Test
    void unknownTeacherSubjectFallsThroughToNormalHandling() throws Exception {
        // Un JWT de docente sin fila (p. ej. borrado): el filtro no bloquea y el servicio responde como antes.
        mockMvc.perform(get("/api/v1/teachers/classrooms").with(teacher(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    private Teacher newTeacher(boolean mustChangePassword) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new Teacher("doc-" + suffix, suffix + "@c.edu", null, "C",
                passwordEncoder.encode("Temporary23"), null, mustChangePassword, null);
    }

    private static RequestPostProcessor teacher(UUID teacherId) {
        return jwt().jwt(token -> token.subject(teacherId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }
}
