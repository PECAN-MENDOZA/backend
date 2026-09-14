package com.mvp.backend.research.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.research.application.dto.AccessCodeResponse;
import com.mvp.backend.research.application.dto.CreateProtocolRequest;
import com.mvp.backend.research.application.dto.CreateStudyRequest;
import com.mvp.backend.research.application.dto.ExperimentRunResponse;
import com.mvp.backend.research.application.dto.ParticipantResponse;
import com.mvp.backend.research.application.dto.ResearchStudyResponse;
import com.mvp.backend.research.application.dto.RunReasonRequest;
import com.mvp.backend.research.application.dto.StudyProtocolResponse;
import com.mvp.backend.research.application.service.ResearchStudyService;

/** Solo investigadores; el identificador del investigador proviene exclusivamente del JWT. */
@RestController
@RequestMapping("/api/v1/research/studies")
@PreAuthorize("hasRole('RESEARCHER')")
public class ResearchStudyController {

    private final ResearchStudyService service;

    public ResearchStudyController(ResearchStudyService service) {
        this.service = service;
    }

    @GetMapping
    public List<ResearchStudyResponse> listStudies(@AuthenticationPrincipal Jwt jwt) {
        return service.listStudies(researcherId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchStudyResponse createStudy(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStudyRequest request) {
        return service.createStudy(researcherId(jwt), request);
    }

    @PostMapping("/{studyId}/protocols")
    @ResponseStatus(HttpStatus.CREATED)
    public StudyProtocolResponse createProtocol(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @Valid @RequestBody CreateProtocolRequest request) {
        return service.createProtocol(researcherId(jwt), studyId, request);
    }

    @PostMapping("/{studyId}/protocols/{protocolId}/activate")
    public StudyProtocolResponse activateProtocol(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID protocolId) {
        return service.activateProtocol(researcherId(jwt), studyId, protocolId);
    }

    @GetMapping("/{studyId}/participants")
    public List<ParticipantResponse> listParticipants(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studyId) {
        return service.listParticipants(researcherId(jwt), studyId);
    }

    @PostMapping("/{studyId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantResponse createParticipant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studyId) {
        return service.createParticipant(researcherId(jwt), studyId);
    }

    @PostMapping("/{studyId}/participants/{participantId}/access-code")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessCodeResponse generateAccessCode(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID participantId) {
        return service.generateAccessCode(researcherId(jwt), studyId, participantId);
    }

    @PostMapping("/{studyId}/access-codes/{runId}/revoke")
    public ExperimentRunResponse revokeAccessCode(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID runId) {
        return service.revokeAccessCode(researcherId(jwt), studyId, runId);
    }

    @GetMapping("/{studyId}/runs")
    public List<ExperimentRunResponse> listRuns(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studyId) {
        return service.listRuns(researcherId(jwt), studyId);
    }

    @PostMapping("/{studyId}/runs/{runId}/exclude")
    public ExperimentRunResponse excludeRun(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID runId,
            @Valid @RequestBody RunReasonRequest request) {
        return service.excludeRun(researcherId(jwt), studyId, runId, request);
    }

    @PostMapping("/{studyId}/runs/{runId}/cancel")
    public ExperimentRunResponse cancelRun(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID runId,
            @Valid @RequestBody RunReasonRequest request) {
        return service.cancelRun(researcherId(jwt), studyId, runId, request);
    }

    private static UUID researcherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
