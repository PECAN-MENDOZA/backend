package com.mvp.backend.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Reglas por URL de SecurityConfig: un rol ajeno recibe 403 aunque la ruta no exista
 * (si solo actuara @PreAuthorize, una ruta inexistente responderia 404).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityRulesTests {

    @Autowired private MockMvc mockMvc;

    @Test
    void researcherIsRejectedUnderTeacherPrefixes() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/classrooms").with(role("RESEARCHER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/teachers/no-such-route").with(role("RESEARCHER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reports/students/" + UUID.randomUUID() + "/pdf").with(role("RESEARCHER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentIsRejectedUnderKpiPrefix() throws Exception {
        mockMvc.perform(get("/api/v1/kpis/students/" + UUID.randomUUID() + "/summary").with(role("STUDENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/kpis/no-such-route").with(role("STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherAndResearcherAreRejectedUnderStudentPrefixes() throws Exception {
        mockMvc.perform(get("/api/v1/students/me").with(role("TEACHER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/corrections/sessions").with(role("RESEARCHER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/corrections/no-such-route").with(role("TEACHER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/classrooms")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/kpis/students/" + UUID.randomUUID() + "/summary"))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
