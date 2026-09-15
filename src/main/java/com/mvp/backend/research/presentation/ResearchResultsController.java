package com.mvp.backend.research.presentation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.research.application.dto.AnnotationCsvFile;
import com.mvp.backend.research.application.dto.StudyResultsResponse;
import com.mvp.backend.research.application.service.StudyMetricsService;

/**
 * Resultados de un estudio (spec §9.4): PEO, PPM, TAS y TAS aceptada con procedencia, mas la exportacion
 * de analisis. Solo investigadores propietarios; el identificador proviene exclusivamente del JWT.
 */
@RestController
@RequestMapping("/api/v1/research/studies/{studyId}")
@PreAuthorize("hasRole('RESEARCHER')")
public class ResearchResultsController {

    private static final MediaType CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final StudyMetricsService service;

    public ResearchResultsController(StudyMetricsService service) {
        this.service = service;
    }

    @GetMapping("/results")
    public StudyResultsResponse results(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studyId) {
        return service.results(researcherId(jwt), studyId);
    }

    @GetMapping("/analysis.csv")
    public ResponseEntity<byte[]> analysisCsv(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studyId) {
        AnnotationCsvFile file = service.analysisCsv(researcherId(jwt), studyId);
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .body(file.bytes());
    }

    private static UUID researcherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
