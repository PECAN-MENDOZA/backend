package com.mvp.backend.sentencetest.application.dto;

import java.util.UUID;

/** Prueba asignada al alumno; status: PENDING, IN_PROGRESS o COMPLETED. */
public record AssignedTestResponse(UUID testId, String code, String title, int sentenceCount, String status) {
}
