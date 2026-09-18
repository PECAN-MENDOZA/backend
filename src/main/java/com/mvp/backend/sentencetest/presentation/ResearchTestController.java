package com.mvp.backend.sentencetest.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.sentencetest.application.dto.AnnotateResponseRequest;
import com.mvp.backend.sentencetest.application.dto.AssignTestRequest;
import com.mvp.backend.sentencetest.application.dto.AssignmentStatusResponse;
import com.mvp.backend.sentencetest.application.dto.AttemptDetailResponse;
import com.mvp.backend.sentencetest.application.dto.CreateTestRequest;
import com.mvp.backend.sentencetest.application.dto.ExcludeAttemptRequest;
import com.mvp.backend.sentencetest.application.dto.TestDetailResponse;
import com.mvp.backend.sentencetest.application.dto.TestResultsResponse;
import com.mvp.backend.sentencetest.application.dto.TestSummaryResponse;
import com.mvp.backend.sentencetest.application.dto.UpdateTestRequest;
import com.mvp.backend.sentencetest.application.service.ResearchTestService;
import com.mvp.backend.sentencetest.application.service.TestExportCsv;
import com.mvp.backend.sentencetest.application.service.TestResultsService;

import jakarta.validation.Valid;

/** API del investigador: pruebas, asignacion, intentos, exclusion, anotacion, resultados y exportacion. */
@RestController
@RequestMapping("/api/v1/research")
@PreAuthorize("hasRole('RESEARCHER')")
public class ResearchTestController {

    private static final MediaType TEXT_CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final ResearchTestService service;
    private final TestResultsService resultsService;

    public ResearchTestController(ResearchTestService service, TestResultsService results) {
        this.service = service;
        this.resultsService = results;
    }

    @PostMapping("/tests")
    @ResponseStatus(HttpStatus.CREATED)
    public TestSummaryResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTestRequest request) {
        return service.createTest(researcherId(jwt), request);
    }

    @GetMapping("/tests")
    public List<TestSummaryResponse> list() {
        return service.listTests();
    }

    @GetMapping("/tests/{testId}")
    public TestDetailResponse get(@PathVariable UUID testId) {
        return service.getTest(testId);
    }

    @PutMapping("/tests/{testId}")
    public TestDetailResponse update(@PathVariable UUID testId, @Valid @RequestBody UpdateTestRequest request) {
        return service.updateTest(testId, request);
    }

    @PostMapping("/tests/{testId}/activate")
    public TestDetailResponse activate(@PathVariable UUID testId) {
        return service.activateTest(testId);
    }

    @PostMapping("/tests/{testId}/close")
    public TestDetailResponse close(@PathVariable UUID testId) {
        return service.closeTest(testId);
    }

    @PostMapping("/tests/{testId}/assignments")
    public List<AssignmentStatusResponse> assign(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID testId, @Valid @RequestBody AssignTestRequest request) {
        return service.assign(researcherId(jwt), testId, request);
    }

    @GetMapping("/tests/{testId}/assignments")
    public List<AssignmentStatusResponse> assignments(@PathVariable UUID testId) {
        return service.assignments(testId);
    }

    @GetMapping("/tests/{testId}/attempts/{attemptId}")
    public AttemptDetailResponse attempt(@PathVariable UUID testId, @PathVariable UUID attemptId) {
        return service.attemptDetail(testId, attemptId);
    }

    @PostMapping("/tests/{testId}/attempts/{attemptId}/exclude")
    public AttemptDetailResponse exclude(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID testId, @PathVariable UUID attemptId,
            @Valid @RequestBody ExcludeAttemptRequest request) {
        return service.excludeAttempt(researcherId(jwt), testId, attemptId, request.reason());
    }

    @PutMapping("/responses/{responseId}/annotation")
    public AttemptDetailResponse.ResponseRow annotate(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID responseId,
            @Valid @RequestBody AnnotateResponseRequest request) {
        return service.annotate(researcherId(jwt), responseId, request.errorCount());
    }

    @GetMapping("/tests/{testId}/results")
    public TestResultsResponse results(@PathVariable UUID testId) {
        return resultsService.results(testId);
    }

    /** CSV de respuestas (UTF-8 con BOM); X-Dataset-Sha256 coincide con datasetSha256 del JSON de resultados. */
    @GetMapping("/tests/{testId}/export.csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable UUID testId) {
        TestResultsService.CsvExport export = resultsService.exportCsv(testId);
        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + TestExportCsv.fileName(export.code()) + "\"")
                .header("X-Dataset-Sha256", export.sha256())
                .body(export.bytes());
    }

    private static UUID researcherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
