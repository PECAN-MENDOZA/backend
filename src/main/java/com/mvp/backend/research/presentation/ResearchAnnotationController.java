package com.mvp.backend.research.presentation;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mvp.backend.research.application.dto.AnnotationBatchResponse;
import com.mvp.backend.research.application.dto.AnnotationCsvFile;
import com.mvp.backend.research.application.service.ResearchAnnotationService;
import com.mvp.backend.research.domain.model.AnnotationKind;
import com.mvp.backend.research.domain.model.AnnotationSlot;
import com.mvp.backend.shared.exception.BusinessException;

/**
 * Lotes ciegos de anotacion (spec §10). Solo investigadores; el identificador del investigador
 * proviene exclusivamente del JWT. Las respuestas JSON nunca contienen filas ni correspondencias.
 */
@RestController
@RequestMapping("/api/v1/research/studies/{studyId}/annotation-batches")
@PreAuthorize("hasRole('RESEARCHER')")
public class ResearchAnnotationController {

    private static final MediaType CSV_UTF8 = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final ResearchAnnotationService service;

    public ResearchAnnotationController(ResearchAnnotationService service) {
        this.service = service;
    }

    @GetMapping
    public List<AnnotationBatchResponse> listBatches(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studyId) {
        return service.listBatches(researcherId(jwt), studyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnotationBatchResponse createBatch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @RequestParam AnnotationKind kind) {
        return service.createBatch(researcherId(jwt), studyId, kind);
    }

    @GetMapping("/{batchId}")
    public AnnotationBatchResponse getBatch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID batchId) {
        return service.getBatch(researcherId(jwt), studyId, batchId);
    }

    @GetMapping("/{batchId}/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID batchId) {
        AnnotationCsvFile file = service.export(researcherId(jwt), studyId, batchId);
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .body(file.bytes());
    }

    @PostMapping(value = "/{batchId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AnnotationBatchResponse importScores(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studyId,
            @PathVariable UUID batchId,
            @RequestParam AnnotationSlot slot,
            @RequestParam String rater,
            @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Could not read the uploaded file");
        }
        return service.importScores(researcherId(jwt), studyId, batchId, slot, rater, content);
    }

    private static UUID researcherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
