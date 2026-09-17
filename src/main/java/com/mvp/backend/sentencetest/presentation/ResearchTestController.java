package com.mvp.backend.sentencetest.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import com.mvp.backend.sentencetest.application.dto.TestSummaryResponse;
import com.mvp.backend.sentencetest.application.dto.UpdateTestRequest;
import com.mvp.backend.sentencetest.application.service.ResearchTestService;

import jakarta.validation.Valid;

/** API del investigador: pruebas, asignacion, intentos, exclusion y anotacion. */
@RestController
@RequestMapping("/api/v1/research")
@PreAuthorize("hasRole('RESEARCHER')")
public class ResearchTestController {

    private final ResearchTestService service;

    public ResearchTestController(ResearchTestService service) {
        this.service = service;
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

    private static UUID researcherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
