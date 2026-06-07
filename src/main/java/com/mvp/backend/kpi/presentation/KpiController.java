package com.mvp.backend.kpi.presentation;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.kpi.application.dto.AcceptanceRateResponse;
import com.mvp.backend.kpi.application.dto.KpiSummaryResponse;
import com.mvp.backend.kpi.application.dto.TopWordsResponse;
import com.mvp.backend.kpi.application.service.KpiService;

@RestController
@RequestMapping("/api/v1/kpis/students/{studentId}")
@PreAuthorize("hasRole('TEACHER')")
public class KpiController {

    private final KpiService kpiService;

    public KpiController(KpiService kpiService) {
        this.kpiService = kpiService;
    }

    @GetMapping("/acceptance-rate")
    public AcceptanceRateResponse acceptanceRate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam String month) {
        return kpiService.acceptanceRate(UUID.fromString(jwt.getSubject()), studentId, month);
    }

    @GetMapping("/top-words")
    public TopWordsResponse topWords(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam String month) {
        return kpiService.topWords(UUID.fromString(jwt.getSubject()), studentId, month);
    }

    @GetMapping("/summary")
    public KpiSummaryResponse summary(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam String month) {
        return kpiService.summary(UUID.fromString(jwt.getSubject()), studentId, month);
    }
}
