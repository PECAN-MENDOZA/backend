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

import com.mvp.backend.report.application.dto.ReportAvailabilityResponse;
import com.mvp.backend.report.application.dto.ReportPdfDownload;
import com.mvp.backend.report.application.service.ReportService;

@RestController
@RequestMapping("/api/v1/reports/students/{studentId}")
@PreAuthorize("hasRole('TEACHER')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public ReportAvailabilityResponse availability(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam String month) {
        return reportService.availability(UUID.fromString(jwt.getSubject()), studentId, month);
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> downloadPdf(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @RequestParam String month) {
        ReportPdfDownload pdf = reportService.downloadPdf(UUID.fromString(jwt.getSubject()), studentId, month);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pdf.filename())
                        .build()
                        .toString())
                .body(pdf.content());
    }
}
