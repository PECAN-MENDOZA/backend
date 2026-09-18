package com.mvp.backend.insights.application.dto;

import java.util.List;
import java.util.UUID;

/** Errores del salon en un periodo, agrupados por tipo. */
public record ClassroomErrorsResponse(
        UUID classroomId,
        String from,
        String to,
        long total,
        List<ErrorTypeBreakdown> types) {
}
