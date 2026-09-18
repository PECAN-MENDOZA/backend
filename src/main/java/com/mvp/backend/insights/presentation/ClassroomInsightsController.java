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

import com.mvp.backend.insights.application.dto.ClassroomActivityResponse;
import com.mvp.backend.insights.application.dto.ClassroomErrorsResponse;
import com.mvp.backend.insights.application.dto.RecentCorrectionItem;
import com.mvp.backend.insights.application.service.ClassroomInsightsService;

/** Panel del docente: instantanea descriptiva del salon en un dia o rango (from/to en Lima). */
@RestController
@RequestMapping("/api/v1/teachers/classrooms/{classroomId}")
@PreAuthorize("hasRole('TEACHER')")
public class ClassroomInsightsController {

    private final ClassroomInsightsService insightsService;

    public ClassroomInsightsController(ClassroomInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/activity")
    public ClassroomActivityResponse activity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classroomId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return insightsService.activity(teacherId(jwt), classroomId, from, to);
    }

    @GetMapping("/corrections/recent")
    public List<RecentCorrectionItem> recentCorrections(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classroomId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        return insightsService.recentCorrections(teacherId(jwt), classroomId, from, to, limit);
    }

    @GetMapping("/errors")
    public ClassroomErrorsResponse errors(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classroomId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return insightsService.errors(teacherId(jwt), classroomId, from, to);
    }

    private static UUID teacherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
