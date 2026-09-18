package com.mvp.backend.insights.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.insights.application.dto.LiveAttemptItem;
import com.mvp.backend.insights.application.service.LiveTestsService;

/** Panel del docente: prueba en curso de sus alumnos vinculados (el portal la consulta cada 5 s). */
@RestController
@PreAuthorize("hasRole('TEACHER')")
public class LiveTestsController {

    private final LiveTestsService liveTestsService;

    public LiveTestsController(LiveTestsService liveTestsService) {
        this.liveTestsService = liveTestsService;
    }

    @GetMapping("/api/v1/teachers/tests/live")
    public List<LiveAttemptItem> live(@AuthenticationPrincipal Jwt jwt) {
        return liveTestsService.live(UUID.fromString(jwt.getSubject()));
    }
}
