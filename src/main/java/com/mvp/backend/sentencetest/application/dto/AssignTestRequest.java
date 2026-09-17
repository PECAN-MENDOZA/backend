package com.mvp.backend.sentencetest.application.dto;

import java.util.List;
import java.util.UUID;

/** Asignacion por salon ({@code classroomId}) o por alumnos sueltos ({@code studentIds}); exactamente uno de los dos. */
public record AssignTestRequest(UUID classroomId, List<UUID> studentIds) {
}
