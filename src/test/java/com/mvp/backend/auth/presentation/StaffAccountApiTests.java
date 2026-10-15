package com.mvp.backend.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

/** Contrato 6 a 9: cambio de contrasena voluntario, correo sin mayusculas, nombre visible y limite de intentos. */
@SpringBootTest
@AutoConfigureMockMvc
class StaffAccountApiTests {

    private static final String CHANGE = "/api/v1/auth/staff/change-password";

    @Autowired private MockMvc mockMvc;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ResearcherRepository researcherRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String suffix;
    private Teacher teacher;
    private Researcher researcher;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        teacher = teacherRepository.save(new Teacher("ana-" + suffix, "ana-" + suffix + "@colegio.edu.pe", null,
                "Colegio", passwordEncoder.encode("Docente2026"), null, true, "Ana QA Torres"));
        researcher = researcherRepository.save(
                new Researcher("inv-" + suffix + "@tesis.edu.pe", passwordEncoder.encode("Investiga2026")));
    }

    @Test
    void teacherChangesPasswordVoluntarilyAndTheTemporaryFlagIsCleared() throws Exception {
        mockMvc.perform(change(as(teacher.getId(), "TEACHER"), "Docente2026", "NuevaClave2026"))
                .andExpect(status().isNoContent());

        Teacher saved = teacherRepository.findById(teacher.getId()).orElseThrow();
        assertThat(saved.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches("NuevaClave2026", saved.getPasswordHash())).isTrue();
    }

    @Test
    void researcherChangesPassword() throws Exception {
        mockMvc.perform(change(as(researcher.getId(), "RESEARCHER"), "Investiga2026", "OtraClave2026"))
                .andExpect(status().isNoContent());

        mockMvc.perform(staffLogin(researcher.getEmail(), "OtraClave2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("RESEARCHER"))
                .andExpect(jsonPath("$.displayName").isEmpty());
    }

    @Test
    void changePasswordErrors() throws Exception {
        RequestPostProcessor auth = as(researcher.getId(), "RESEARCHER");
        mockMvc.perform(change(auth, "Equivocada1", "OtraClave2026")).andExpect(status().isUnauthorized());
        mockMvc.perform(change(auth, "Investiga2026", "Investiga2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password must be different"));
        mockMvc.perform(change(auth, "Investiga2026", "corta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.newPassword").exists());
        mockMvc.perform(change(auth, "Investiga2026", "x".repeat(73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.newPassword").exists());
        // Sin token o con rol de alumno no se puede.
        mockMvc.perform(post(CHANGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"OtraClave2026\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(change(as(UUID.randomUUID(), "STUDENT"), "Investiga2026", "OtraClave2026"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginIgnoresEmailCaseAndReturnsDisplayName() throws Exception {
        String upper = teacher.getEmail().toUpperCase();
        mockMvc.perform(staffLogin(upper, "Docente2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TEACHER"))
                .andExpect(jsonPath("$.displayName").value("Ana QA Torres"));
        mockMvc.perform(post("/api/v1/auth/teachers/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + upper + "\",\"password\":\"Docente2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ana QA Torres"));
    }

    @Test
    void createdTeacherEmailIsStoredLowercaseAndDuplicatesIgnoreCase() throws Exception {
        String email = "Nuevo.Docente-" + suffix + "@Colegio.edu.pe";
        mockMvc.perform(post("/api/v1/research/teachers").with(as(researcher.getId(), "RESEARCHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Nuevo Docente\",\"email\":\"" + email + "\",\"institution\":\"C\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()));
        mockMvc.perform(post("/api/v1/research/teachers").with(as(researcher.getId(), "RESEARCHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Otro\",\"email\":\"" + email.toUpperCase() + "\",\"institution\":\"C\"}"))
                .andExpect(status().isConflict());
        // Tampoco puede repetir el correo del investigador con otras mayusculas.
        mockMvc.perform(post("/api/v1/research/teachers").with(as(researcher.getId(), "RESEARCHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Otro\",\"email\":\"" + researcher.getEmail().toUpperCase()
                                + "\",\"institution\":\"C\"}"))
                .andExpect(status().isConflict());
    }

    private static RequestBuilder change(RequestPostProcessor auth, String current, String next) {
        return post(CHANGE).with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
    }

    private static RequestBuilder staffLogin(String email, String password) {
        return post("/api/v1/auth/staff/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static RequestPostProcessor as(UUID id, String role) {
        return jwt().jwt(token -> token.subject(id.toString()).claim("role", role))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
