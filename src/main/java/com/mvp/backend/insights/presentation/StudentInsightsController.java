package com.mvp.backend.insights.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.insights.application.dto.StudentErrorsResponse;
import com.mvp.backend.insights.application.dto.StudentHelpResponse;
import com.mvp.backend.insights.application.dto.StudentTestSummary;
import com.mvp.backend.insights.application.dto.StudentWritingItem;
import com.mvp.backend.insights.application.service.StudentInsightsService;

/** Panel del docente: ficha descriptiva de un alumno vinculado en un dia o rango (from/to en Lima). */
@RestController
@RequestMapping("/api/v1/teachers/students/{studentId}")
@PreAuthorize("hasRole('TEACHER')")
public class StudentInsightsController {

    private final StudentInsightsService insightsService;

    public StudentInsightsController(StudentInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/errors")
    public StudentErrorsResponse errors(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return insightsService.errors(teacherId(jwt), studentId, from, to);
    }

    @GetMapping("/help")
    public StudentHelpResponse help(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return insightsService.help(teacherId(jwt), studentId, from, to);
    }

    @GetMapping("/writings")
    public List<StudentWritingItem> writings(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        return insightsService.writings(teacherId(jwt), studentId, from, to, limit);
    }

    @GetMapping("/tests")
    public List<StudentTestSummary> tests(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId) {
        return insightsService.tests(teacherId(jwt), studentId);
    }

    private static UUID teacherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
