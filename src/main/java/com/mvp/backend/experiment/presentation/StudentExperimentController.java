package com.mvp.backend.experiment.presentation;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.experiment.application.dto.CancelExperimentRequest;
import com.mvp.backend.experiment.application.dto.CompleteExperimentRequest;
import com.mvp.backend.experiment.application.dto.ExperimentRunResponse;
import com.mvp.backend.experiment.application.dto.RedeemAccessCodeRequest;
import com.mvp.backend.experiment.application.service.StudentExperimentService;

/** Solo alumnos; el identificador del alumno proviene exclusivamente del JWT. */
@RestController
@RequestMapping("/api/v1/experiments")
@PreAuthorize("hasRole('STUDENT')")
public class StudentExperimentController {

    private final StudentExperimentService service;

    public StudentExperimentController(StudentExperimentService service) {
        this.service = service;
    }

    @PostMapping("/access-code/redeem")
    @ResponseStatus(HttpStatus.CREATED)
    public ExperimentRunResponse redeem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RedeemAccessCodeRequest request) {
        return service.redeem(studentId(jwt), request);
    }

    @PostMapping("/runs/{runId}/start")
    public ExperimentRunResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        return service.start(studentId(jwt), runId);
    }

    @GetMapping("/runs/active")
    public ExperimentRunResponse active(@AuthenticationPrincipal Jwt jwt) {
        return service.active(studentId(jwt));
    }

    @PatchMapping("/runs/{runId}/complete")
    public ExperimentRunResponse complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId,
            @Valid @RequestBody CompleteExperimentRequest request) {
        return service.complete(studentId(jwt), runId, request);
    }

    @PostMapping("/runs/{runId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId,
            @Valid @RequestBody CancelExperimentRequest request) {
        service.cancel(studentId(jwt), runId, request);
    }

    private static UUID studentId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
