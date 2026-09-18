package com.mvp.backend.sentencetest.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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

import com.mvp.backend.sentencetest.application.dto.AssignedTestResponse;
import com.mvp.backend.sentencetest.application.dto.AttemptResponse;
import com.mvp.backend.sentencetest.application.dto.CancelAttemptRequest;
import com.mvp.backend.sentencetest.application.dto.FinishSentenceRequest;
import com.mvp.backend.sentencetest.application.dto.FinishSentenceResponse;
import com.mvp.backend.sentencetest.application.dto.StartAttemptRequest;
import com.mvp.backend.sentencetest.application.dto.StartSentenceResponse;
import com.mvp.backend.sentencetest.application.service.StudentTestService;

import jakarta.validation.Valid;

/** API del alumno (teclado): pruebas asignadas, intento y Comenzar/Terminar por oracion. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('STUDENT')")
public class StudentTestController {

    private final StudentTestService service;

    public StudentTestController(StudentTestService service) {
        this.service = service;
    }

    @GetMapping("/tests/assigned")
    public List<AssignedTestResponse> assigned(@AuthenticationPrincipal Jwt jwt) {
        return service.assignedTests(studentId(jwt));
    }

    /** 201 si se creo el intento; 200 si se devuelve el intento en curso de esa misma prueba. */
    @PostMapping("/tests/{testId}/attempts")
    public ResponseEntity<AttemptResponse> startAttempt(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID testId, @Valid @RequestBody StartAttemptRequest request) {
        StudentTestService.StartedAttempt started = service.startAttempt(studentId(jwt), testId, request);
        return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK).body(started.attempt());
    }

    @PostMapping("/attempts/{attemptId}/responses/{position}/start")
    public StartSentenceResponse startSentence(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID attemptId, @PathVariable int position) {
        return service.startSentence(studentId(jwt), attemptId, position);
    }

    @PutMapping("/attempts/{attemptId}/responses/{position}")
    public FinishSentenceResponse finishSentence(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID attemptId, @PathVariable int position,
            @Valid @RequestBody FinishSentenceRequest request) {
        return service.finishSentence(studentId(jwt), attemptId, position, request);
    }

    @PostMapping("/attempts/{attemptId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID attemptId, @Valid @RequestBody CancelAttemptRequest request) {
        service.cancelAttempt(studentId(jwt), attemptId, request);
    }

    private static UUID studentId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
