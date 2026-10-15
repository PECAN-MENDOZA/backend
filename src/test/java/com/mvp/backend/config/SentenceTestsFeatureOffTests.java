package com.mvp.backend.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Con el modulo de pruebas apagado sus endpoints no existen (404) y el resto sigue en pie. */
@SpringBootTest(properties = "app.features.sentence-tests=false")
@AutoConfigureMockMvc
class SentenceTestsFeatureOffTests {

    @Autowired private MockMvc mockMvc;

    @Test
    void researcherTestEndpointsAreGone() throws Exception {
        mockMvc.perform(get("/api/v1/research/tests").with(as("RESEARCHER"))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/research/tests").with(as("RESEARCHER"))).andExpect(status().isNotFound());
    }

    @Test
    void studentTestEndpointsAreGone() throws Exception {
        mockMvc.perform(get("/api/v1/tests/assigned").with(as("STUDENT"))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/tests/{id}/attempts", UUID.randomUUID()).with(as("STUDENT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherTestEndpointsAreGone() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/tests/live").with(as("TEACHER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/teachers/students/{id}/tests", UUID.randomUUID()).with(as("TEACHER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void restOfTheTeacherPanelStillAnswers() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/classrooms").with(as("TEACHER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/research/teachers").with(as("RESEARCHER"))).andExpect(status().isOk());
    }

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
