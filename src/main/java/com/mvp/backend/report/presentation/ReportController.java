package com.mvp.backend.report.presentation;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.report.application.dto.ReportPdfDownload;
import com.mvp.backend.report.application.service.ReportService;

/** Reporte del periodo en PDF para un alumno vinculado (from/to en Lima, como el resto del panel). */
@RestController
@RequestMapping("/api/v1/reports/students/{studentId}")
@PreAuthorize("hasRole('TEACHER')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        ReportPdfDownload pdf = reportService.downloadPdf(UUID.fromString(jwt.getSubject()), studentId, from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pdf.filename())
                        .build()
                        .toString())
                .body(pdf.content());
    }
}
