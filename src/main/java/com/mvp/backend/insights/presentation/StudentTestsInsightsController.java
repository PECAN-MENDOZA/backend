package com.mvp.backend.insights.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.insights.application.dto.StudentTestSummary;
import com.mvp.backend.insights.application.service.StudentInsightsService;

/** Ficha del alumno: sus pruebas de oraciones. Solo existe con el modulo de pruebas encendido. */
@ConditionalOnProperty(prefix = "app.features", name = "sentence-tests", havingValue = "true")
@RestController
@PreAuthorize("hasRole('TEACHER')")
public class StudentTestsInsightsController {

    private final StudentInsightsService insightsService;

    public StudentTestsInsightsController(StudentInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/api/v1/teachers/students/{studentId}/tests")
    public List<StudentTestSummary> tests(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId) {
        return insightsService.tests(UUID.fromString(jwt.getSubject()), studentId);
    }
}
